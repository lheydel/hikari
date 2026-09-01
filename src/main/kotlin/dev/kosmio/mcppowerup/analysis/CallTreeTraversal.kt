package dev.kosmio.mcppowerup.analysis

import com.intellij.lang.Language
import com.intellij.mcpserver.mcpFail
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import dev.kosmio.mcppowerup.ProjectPaths
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

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

    /** The seed, resolved, with the two facts about it the result reports alongside the tree. */
    private class Seed(val node: WorkNode, val language: String, val providers: List<String>)

    /** Per-file `qualified name -> offsets in document order`, the index [symbolFor] assigns `#N` from. */
    private val ordinalIndexes = HashMap<String, Map<String, List<Int>>>()

    private val providersByLanguage = HashMap<Language, List<SuperDeclarationProvider>>()

    suspend fun run(seedRef: NodeRef, maxDepth: Int, format: String): CallTreeResult {
        val vFile = findVirtualFile(seedRef.file)
            ?: mcpFail("File '${seedRef.file}' not found under the project. Pass a project-relative path.")
        val staleness = stalenessOf(vFile, seedRef.file)
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
        return CallTreeResult(
            seed = seed.node.ref,
            depth = maxDepth,
            status = status,
            callerCount = seen.size - 1,
            superHop = SuperHop(seed.language, seed.providers.isNotEmpty(), seed.providers),
            staleness = staleness,
            grouped = if (format == FORMAT_GROUPED) groupedOf(seed.node) else null,
            tree = if (format == FORMAT_TREE) treeOf(seed.node, unexpanded) else null,
            frontier = frontierRefs,
        )
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
     * The declaration a reference belongs to: its nearest enclosing [PsiNameIdentifierOwner] carrying a
     * real name. That predicate is language-neutral and answers the question that actually matters — is
     * this a declaration rather than a file or markup. A [PsiFile] is not one and neither is a JSX
     * literal, so they drop out, while the property declarations, which are real call sites, stay.
     */
    private fun enclosingDeclaration(from: PsiElement): PsiElement? {
        var current: PsiElement? = from.parent
        while (current != null && current !is PsiFile) {
            if (isDeclaration(current)) return current
            current = current.parent
        }
        return null
    }

    /**
     * Whether an element is a declaration a caller can be attributed to. The platform gives synthetic
     * elements a placeholder name in angle brackets rather than no name at all — a Kotlin lambda answers
     * `<anonymous>`, not null — and those are dead ends: nothing references a lambda, so a tree that
     * stopped at one would report a leaf where the enclosing function is the real caller.
     */
    private fun isDeclaration(element: PsiElement): Boolean {
        if (element !is PsiNameIdentifierOwner) return false
        val name = element.name ?: return false
        return !name.startsWith("<")
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
        val symbol = symbolFor(element, relativePath, psiFile) ?: return null
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
                ?: mcpFail("'${seedRef.symbol}' selects declaration #$ordinal of ${matches.size} named '$name' in ${seedRef.file}.${staleSuffix(staleness)}")
            matches.size == 1 -> matches.single()
            matches.isEmpty() -> mcpFail(
                "No declaration named '$name' in ${seedRef.file}.${staleSuffix(staleness)} " +
                    "Declarations there: ${declarations.mapNotNull { qualifiedName(it) }.distinct().joinToString(", ")}",
            )
            else -> mcpFail(
                "'$name' is ambiguous in ${seedRef.file}. Re-run with one of these exact symbol values: " +
                    matches.mapNotNull { symbolFor(it, seedRef.file, psiFile) }.joinToString(", "),
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
        val vFile = findVirtualFile(ref.file) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: return null
        val (name, ordinal) = parseSymbol(ref.symbol)
        val matches = matchesFor(declarationsOf(psiFile), name)
        return if (ordinal != null) matches.getOrNull(ordinal - 1) else matches.singleOrNull()
    }

    /**
     * The declarations [name] selects: the ones whose qualified name is exactly [name], or — when a
     * caller passed a bare name rather than a qualified one — the ones it is the trailing segment of.
     */
    private fun matchesFor(declarations: List<PsiElement>, name: String): List<PsiElement> {
        val exact = declarations.filter { qualifiedName(it) == name }
        if (exact.isNotEmpty()) return exact
        return declarations.filter { qualifiedName(it)?.endsWith(".$name") == true }
    }

    /**
     * The declaration's name qualified by its enclosing declarations, with a `#N` suffix when the file
     * holds more than one declaration under that qualified name. That suffix is what makes every [NodeRef]
     * this traversal emits resolve back to exactly one declaration.
     */
    private fun symbolFor(element: PsiElement, relativePath: String, psiFile: PsiFile): String? {
        val qualified = qualifiedName(element) ?: return null
        val offsets = ordinalIndexes.getOrPut(relativePath) { indexOrdinals(psiFile) }[qualified]
        if (offsets == null || offsets.size <= 1) return qualified
        val position = offsets.indexOf(element.textOffset)
        return if (position < 0) qualified else "$qualified#${position + 1}"
    }

    /**
     * The declaration's name qualified by the enclosing declarations that have real names. The synthetic
     * ones are skipped rather than spelled out, so a property declared inside a lambda reads
     * `UserDeleteFeatureImpl.invoke.deleted` — still unique within the file, with `#N` for what is not.
     */
    private fun qualifiedName(element: PsiElement): String? {
        val parts = mutableListOf<String>()
        var current: PsiElement? = element
        while (current != null && current !is PsiFile) {
            if (isDeclaration(current)) parts += (current as PsiNameIdentifierOwner).name.orEmpty()
            current = current.parent
        }
        return if (parts.isEmpty()) null else parts.asReversed().joinToString(".")
    }

    private fun indexOrdinals(psiFile: PsiFile): Map<String, List<Int>> {
        val index = HashMap<String, MutableList<Int>>()
        for (declaration in declarationsOf(psiFile)) {
            val qualified = qualifiedName(declaration) ?: continue
            index.getOrPut(qualified) { mutableListOf() } += declaration.textOffset
        }
        return index
    }

    /** Every declaration in the file, in document order. */
    private fun declarationsOf(psiFile: PsiFile): List<PsiElement> {
        val out = mutableListOf<PsiElement>()
        fun walk(element: PsiElement) {
            for (child in element.children) {
                if (isDeclaration(child)) out += child
                walk(child)
            }
        }
        walk(psiFile)
        return out
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

    private fun findVirtualFile(path: String): VirtualFile? {
        val absolute = if (path.startsWith("/")) path else "$basePath/$path"
        return LocalFileSystem.getInstance().findFileByPath(absolute)
    }

    /**
     * Whether the IDE's copy of the file still matches what is on disk. An edit written outside the IDE
     * does not reach the VFS until a re-scan, and the answer gives no other sign of it: the
     * traversal runs happily against the file as the IDE last saw it.
     */
    private fun stalenessOf(vFile: VirtualFile, relativePath: String): Staleness {
        val path = try {
            Path.of(vFile.path)
        } catch (_: InvalidPathException) {
            return Staleness(relativePath, true)
        }
        val matches = try {
            Files.exists(path) && Files.size(path) == vFile.length &&
                Files.getLastModifiedTime(path).toMillis() <= vFile.timeStamp
        } catch (_: IOException) {
            return Staleness(relativePath, true)
        }
        return if (matches) Staleness(relativePath, true) else Staleness(relativePath, false, STALE_HINT)
    }

    private fun staleSuffix(staleness: Staleness): String =
        if (staleness.ideCopyMatchesDisk) "" else " $STALE_HINT"

    private fun parseSymbol(symbol: String): Pair<String, Int?> {
        val hash = symbol.lastIndexOf('#')
        if (hash <= 0) return symbol to null
        val ordinal = symbol.substring(hash + 1).toIntOrNull() ?: return symbol to null
        return symbol.substring(0, hash) to ordinal
    }

    /** Flatten the traversal breadth-first, each node pointing at the index of the node it calls. */
    private fun treeOf(seed: WorkNode, unexpanded: Set<String>): List<CallNode> {
        val out = mutableListOf<CallNode>()
        val queue = ArrayDeque<Pair<WorkNode, Int>>()
        queue += seed to -1
        while (queue.isNotEmpty()) {
            val (node, calls) = queue.removeFirst()
            val index = out.size
            out += CallNode(
                symbol = node.ref.symbol,
                file = node.ref.file,
                line = node.line,
                module = node.module,
                isTest = node.isTest,
                calls = calls,
                overrides = node.overrides,
                unexpanded = node.key in unexpanded,
            )
            for (caller in node.callers) queue += caller to index
        }
        return out
    }

    private fun groupedOf(seed: WorkNode): GroupedCallers {
        val all = mutableListOf<WorkNode>()
        fun collect(node: WorkNode) {
            for (caller in node.callers) {
                all += caller
                collect(caller)
            }
        }
        collect(seed)
        val (test, production) = all.partition { it.isTest }
        return GroupedCallers(production = groupByModule(production), test = groupByModule(test))
    }

    private fun groupByModule(nodes: List<WorkNode>): List<CallerModule> =
        nodes.groupBy { it.module }
            .map { (module, inModule) ->
                CallerModule(
                    module = module,
                    callerCount = inModule.size,
                    files = inModule.groupBy { it.fileName }
                        .map { (fileName, inFile) -> CallerFile(fileName, inFile.map { it.ref.symbol }) }
                        .sortedBy { it.file },
                )
            }
            .sortedByDescending { it.callerCount }

    companion object {
        const val FORMAT_GROUPED = "grouped"
        const val FORMAT_TREE = "tree"

        const val STATUS_COMPLETE = "complete"
        const val STATUS_STOPPED_AT_DEPTH = "stopped_at_depth"
        const val STATUS_STOPPED_UNRESOLVED = "stopped_unresolved"

        const val MAX_DEPTH = 5
        const val DEFAULT_DEPTH = 2

        private const val NO_MODULE = "<no module>"

        private const val STALE_HINT =
            "The IDE's copy of this file is older than what is on disk — call refresh_changelists, then run this again."

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
