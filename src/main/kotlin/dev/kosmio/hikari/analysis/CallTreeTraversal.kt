package dev.kosmio.hikari.analysis

import com.intellij.lang.Language
import com.intellij.mcpserver.mcpFail
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import dev.kosmio.hikari.ProjectPaths
import dev.kosmio.hikari.analysis.DeclarationSymbols.Companion.declarationsOf
import dev.kosmio.hikari.analysis.DeclarationSymbols.Companion.isDeclaration
import dev.kosmio.hikari.analysis.DeclarationSymbols.Companion.matchesFor
import dev.kosmio.hikari.analysis.DeclarationSymbols.Companion.parseSymbol
import dev.kosmio.hikari.analysis.DeclarationSymbols.Companion.qualifiedName

/**
 * The reverse call tree: from a seed declaration, breadth-first to what references it, following at each
 * node the references to it and the declarations it overrides (via [SuperDeclarationProvider], the
 * hop that reaches an implementation's callers through its port).
 *
 * One node's expansion is one [smartReadAction]. That granularity is the whole reason the traversal is
 * not a single read action: the platform cancels and *restarts* a smart read action when a write arrives,
 * so a traversal wrapped in one would replay from the start against a user who is typing. It also means
 * no [PsiElement] may survive a unit — a cancelling write can invalidate it — so a node crossing that
 * boundary carries a [SmartPsiElementPointer] plus the [NodeRef] coordinates it can be re-resolved from.
 * Those coordinates are the same ones a stopped run reports as its frontier, so resuming and recovering
 * from cancellation are one mechanism.
 *
 * Everything is confined to project sources: the search scope is [GlobalSearchScope.projectScope], and a
 * node whose file falls outside it is dropped, so a library or SDK declaration never enters the result.
 */
internal class CallTreeTraversal(private val project: Project, private val basePath: String) {

    /** A node of the traversal: what the result needs, plus the handles required to expand it later. */
    private class WorkNode(
        val ref: NodeRef,
        val key: String,
        val pointer: SmartPsiElementPointer<PsiElement>,
        val fileName: String,
        val line: Int,
        val module: String,
        val isTest: Boolean,
        val overrides: Boolean,
    ) {
        val callers = mutableListOf<WorkNode>()
    }

    /** The seed, resolved, with the facts about it the result reports alongside the tree. */
    private class Seed(val node: WorkNode, val language: String, val providers: List<String>)

    private val symbols = DeclarationSymbols()

    private val providersByLanguage = HashMap<Language, List<SuperDeclarationProvider>>()

    suspend fun run(seedRef: NodeRef, maxDepth: Int): String {
        val vFile = SourceFiles.find(basePath, seedRef.file)
            ?: mcpFail("File '${seedRef.file}' not found under the project. Pass a project-relative path.")
        val staleness = SourceFiles.stalenessOf(vFile, seedRef.file)
        val seed = smartReadAction(project) { resolveSeed(vFile, seedRef, staleness) }

        val seen = hashSetOf(seed.node.key)
        val unresolved = mutableListOf<WorkNode>()
        var frontier = listOf(seed.node)
        var level = 0
        while (frontier.isNotEmpty() && level < maxDepth) {
            val next = mutableListOf<WorkNode>()
            for (node in frontier) {
                val found = smartReadAction(project) { expand(node) }
                if (found == null) {
                    // The element was invalidated and its coordinates no longer resolve: report it
                    // unexpanded rather than letting it read as a node with no callers.
                    unresolved += node
                    continue
                }
                for (caller in found) {
                    // A caller reached twice keeps its first (shallowest) position, so the result is a
                    // spanning tree of the caller set rather than a set of repeating paths.
                    if (seen.add(caller.key)) {
                        node.callers += caller
                        next += caller
                    }
                }
            }
            frontier = next
            level++
        }

        val unexpanded = (frontier + unresolved).map { it.key }.toSet()
        val frontierRefs = (frontier + unresolved).map { it.ref }
        val status = when {
            unresolved.isNotEmpty() -> STATUS_STOPPED_UNRESOLVED
            frontierRefs.isEmpty() -> STATUS_COMPLETE
            else -> STATUS_STOPPED_AT_DEPTH
        }
        return (header(seed, maxDepth, status, seen.size - 1, frontierRefs.size, staleness) +
            treeOf(seed.node, unexpanded)).joinToString("\n")
    }

    /**
     * The lines every result opens with: what was asked, what came back, and the facts that decide how
     * an empty answer must be read — whether the override hop resolved, and whether the IDE's copy of
     * the seed file is current.
     */
    private fun header(
        seed: Seed,
        maxDepth: Int,
        status: String,
        callerCount: Int,
        unexpandedCount: Int,
        staleness: Staleness,
    ): List<String> {
        val out = mutableListOf<String>()
        out += "$SEED_ARROW ${seed.node.ref.symbol}  ($callerCount callers, depth $maxDepth, $status)"
        out += if (seed.providers.isEmpty()) {
            "override hop: UNAVAILABLE for ${seed.language} — an empty result says nothing either way here"
        } else {
            "override hop: ${seed.language} via ${seed.providers.joinToString("+")}"
        }
        if (unexpandedCount > 0) {
            out += "$UNEXPANDED_MARK marks the $unexpandedCount node(s) never expanded — re-run at depth " +
                "${maxDepth + 1}, or seed a new call_tree on any of them, to continue"
        }
        if (!staleness.ideCopyMatchesDisk) out += STALE_LINE
        return out
    }

    /**
     * One node's hop: the declarations it overrides, then everything that references it, each mapped to
     * its nearest enclosing declaration. Returns null when the node's element is gone and its coordinates
     * no longer resolve — a caller must not read that as "no callers".
     */
    private fun expand(node: WorkNode): List<WorkNode>? {
        val element = node.pointer.element ?: resolveRef(node.ref) ?: return null
        val found = LinkedHashMap<String, Pair<PsiElement, Boolean>>()
        for (provider in providersFor(element.language)) {
            for (superDeclaration in provider.superDeclarations(element)) {
                val target = superDeclaration.navigationElement ?: superDeclaration
                keyOf(target)?.let { found.putIfAbsent(it, target to true) }
            }
        }
        ReferencesSearch.search(element, GlobalSearchScope.projectScope(project)).findAll().forEach { reference ->
            enclosingDeclaration(reference.element)?.let { declaration ->
                keyOf(declaration)?.let { found.putIfAbsent(it, declaration to false) }
            }
        }
        return found.mapNotNull { (key, hit) -> workNode(key, hit.first, hit.second) }
    }

    /**
     * The declaration a reference belongs to: its nearest enclosing one under
     * [DeclarationSymbols.isDeclaration], the same predicate the symbol grammar is built on, so every
     * node this traversal reports can also be named.
     */
    private fun enclosingDeclaration(from: PsiElement): PsiElement? {
        var current: PsiElement? = from.parent
        while (current != null && current !is PsiFile) {
            if (isDeclaration(current)) return current
            current = current.parent
        }
        return null
    }

    /** A node's identity: its file and the offset of the declaration. Null outside project sources. */
    private fun keyOf(element: PsiElement): String? {
        val vFile = element.containingFile?.virtualFile ?: return null
        if (!GlobalSearchScope.projectScope(project).contains(vFile)) return null
        return vFile.path + "#" + element.textOffset
    }

    private fun workNode(key: String, element: PsiElement, overrides: Boolean): WorkNode? {
        val psiFile = element.containingFile ?: return null
        val vFile = psiFile.virtualFile ?: return null
        val relativePath = ProjectPaths.relativize(basePath, vFile.path)
        val symbol = symbols.symbolFor(element, relativePath, psiFile) ?: return null
        return WorkNode(
            ref = NodeRef(relativePath, symbol),
            key = key,
            pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element),
            fileName = vFile.name,
            line = lineOf(psiFile, element),
            module = ModuleUtilCore.findModuleForFile(vFile, project)?.name ?: NO_MODULE,
            isTest = isTestSource(relativePath, vFile.name),
            overrides = overrides,
        )
    }

    private fun resolveSeed(vFile: VirtualFile, seedRef: NodeRef, staleness: Staleness): Seed {
        val psiFile = PsiManager.getInstance(project).findFile(vFile)
            ?: mcpFail("'${seedRef.file}' has no PSI — it is binary or the IDE does not parse this file type.")
        val declarations = declarationsOf(psiFile)
        val (name, ordinal) = parseSymbol(seedRef.symbol)
        val matches = matchesFor(declarations, name)
        val element = when {
            ordinal != null -> matches.getOrNull(ordinal - 1)
                ?: mcpFail("'${seedRef.symbol}' selects declaration #$ordinal of ${matches.size} named '$name' in ${seedRef.file}.${SourceFiles.staleSuffix(staleness)}")
            matches.size == 1 -> matches.single()
            matches.isEmpty() -> mcpFail(
                "No declaration named '$name' in ${seedRef.file}.${SourceFiles.staleSuffix(staleness)} " +
                    "Declarations there: ${declarations.mapNotNull { qualifiedName(it) }.distinct().joinToString(", ")}",
            )
            else -> mcpFail(
                "'$name' is ambiguous in ${seedRef.file}. Re-run with one of these exact symbol values: " +
                    matches.mapNotNull { symbols.symbolFor(it, seedRef.file, psiFile) }.joinToString(", "),
            )
        }
        val key = keyOf(element)
            ?: mcpFail("'${seedRef.symbol}' resolves outside the project's own sources; call_tree searches project sources only.")
        val node = workNode(key, element, overrides = false)
            ?: mcpFail("'${seedRef.symbol}' could not be resolved to a declaration in ${seedRef.file}.")
        val language = psiFile.language
        return Seed(node, language.id, providersFor(language).map { it.id })
    }

    /** Re-resolve a node from its coordinates after the element behind it was invalidated. */
    private fun resolveRef(ref: NodeRef): PsiElement? {
        val vFile = SourceFiles.find(basePath, ref.file) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: return null
        val (name, ordinal) = parseSymbol(ref.symbol)
        val matches = matchesFor(declarationsOf(psiFile), name)
        return if (ordinal != null) matches.getOrNull(ordinal - 1) else matches.singleOrNull()
    }

    private fun providersFor(language: Language): List<SuperDeclarationProvider> =
        providersByLanguage.getOrPut(language) {
            SuperDeclarationProvider.EP_NAME.extensionList.filter { it.supports(language) }
        }

    private fun lineOf(psiFile: PsiFile, element: PsiElement): Int {
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return 0
        val offset = element.textOffset
        if (offset < 0 || offset > document.textLength) return 0
        return document.getLineNumber(offset) + 1
    }

    /**
     * Whether a file is test source, keyed on its path and name. The source root's kind — what
     * `TestSourcesFilter` and `ProjectFileIndex.isInTestSourceContent` read — is not usable here: a
     * project can put its step definitions in a Gradle *main* source set (`.../test/src/main/kotlin`),
     * where those report false for every node.
     */
    private fun isTestSource(relativePath: String, fileName: String): Boolean {
        val directories = relativePath.split('/').dropLast(1)
        if (directories.any { it in TEST_DIRECTORIES }) return true
        return TEST_FILE_SUFFIXES.any { fileName.endsWith(it) }
    }

    /**
     * The tree, depth-first, one line per node: indentation means "calls the line above".
     *
     * Locations are compressed, because they would otherwise be 70% of the result: every directory is
     * listed once up front as `D<n>`, and a node's location is `D<n>/<file>:<line>`. Where the file is
     * named after the declaration's own outermost segment — which it is for most of a Kotlin or
     * TypeScript codebase — the name is dropped too and the location reads `D<n>.<ext>:<line>`, the file
     * being that segment plus that extension.
     */
    private fun treeOf(seed: WorkNode, unexpanded: Set<String>): List<String> {
        val directories = LinkedHashMap<String, Int>()
        val body = mutableListOf<String>()
        fun walk(node: WorkNode, depth: Int) {
            val marks = buildString {
                if (node.overrides) append(OVERRIDE_MARK)
                if (node.key in unexpanded) append(UNEXPANDED_MARK)
                if (node.isTest) append(TEST_MARK)
            }
            val path = node.ref.file
            val directory = path.substringBeforeLast('/', "")
            val index = directories.getOrPut(directory) { directories.size }
            body += "  ".repeat(depth) + "${node.ref.symbol}$marks  ${location(node, index)}:${node.line}"
            for (caller in node.callers) walk(caller, depth + 1)
        }
        walk(seed, 0)
        if (seed.callers.isEmpty()) return body + NO_CALLERS_LINE
        val table = directories.keys.mapIndexed { index, directory -> "D$index $directory" }
        return listOf(DIRECTORY_HEADING) + table + body
    }

    /**
     * A node's file, against its directory's `D<n>`: the bare name when it cannot be derived from the
     * symbol, and just the extension when it can.
     */
    private fun location(node: WorkNode, directoryIndex: Int): String {
        val fileName = node.fileName
        val outermost = node.ref.symbol.substringBefore('.').substringBefore('#')
        val extension = fileName.substringAfterLast('.', "")
        return if (extension.isNotEmpty() && fileName == "$outermost.$extension") {
            "D$directoryIndex.$extension"
        } else {
            "D$directoryIndex/$fileName"
        }
    }

    companion object {
        const val STATUS_COMPLETE = "complete"
        const val STATUS_STOPPED_AT_DEPTH = "stopped_at_depth"
        const val STATUS_STOPPED_UNRESOLVED = "stopped_unresolved"

        const val MAX_DEPTH = 5
        const val DEFAULT_DEPTH = 2

        private const val NO_MODULE = "<no module>"

        /** Prefixes the seed line, so the direction of the answer is unmistakable: callers OF this. */
        private const val SEED_ARROW = "callers of"

        private const val OVERRIDE_MARK = "^"
        private const val UNEXPANDED_MARK = "*"
        private const val TEST_MARK = " (test)"

        private const val DIRECTORY_HEADING =
            "dirs (a location is D<n>/<file>:<line>; D<n>.<ext> means the file is the declaration's " +
                "outermost segment plus that extension):"

        private const val NO_CALLERS_LINE =
            "no callers found — framework entry points (@Scheduled, @EventListener, @Bean, route files) " +
                "have none by construction, so this is NOT evidence of dead code"

        private const val STALE_LINE =
            "STALE: the IDE has not re-scanned this file since it changed on disk — " +
                "call refresh_changelists, then run this again"

        /** Path segments that mark test sources, whatever Gradle source set they sit in. */
        private val TEST_DIRECTORIES =
            setOf("test", "tests", "testFixtures", "androidTest", "integrationTest", "__tests__", "__mocks__")

        private val TEST_FILE_SUFFIXES = listOf(
            "Test.kt", "Tests.kt", "Spec.kt", "IT.kt", "Test.java", "Tests.java", "IT.java",
            ".test.ts", ".test.tsx", ".test.js", ".test.jsx",
            ".spec.ts", ".spec.tsx", ".spec.js", ".spec.jsx",
        )
    }
}
