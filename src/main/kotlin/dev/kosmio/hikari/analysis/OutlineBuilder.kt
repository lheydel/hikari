package dev.kosmio.hikari.analysis

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.mcpserver.mcpFail
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import dev.kosmio.hikari.analysis.DeclarationSymbols.Companion.isDeclaration

/**
 * A file's declarations with their signatures and line ranges, bodies omitted, so a caller can make a
 * targeted read instead of a whole-file one.
 *
 * Built on the platform's structure-view model rather than a PSI walk of our own: structure views are
 * contributed per language, so one implementation covers every language the IDE understands and each
 * gets its own idea of a signature — Kotlin answers `execute(suspend () -> T): T` — where a hand-rolled
 * walk would need a rule per language and would get "what counts as a declaration" wrong differently in
 * each. The model builds without an open editor, which is what makes it usable from a tool.
 *
 * The structure view offers more than declarations, though: it surfaces the file root, JSX literals and
 * ES6 re-export specifiers as nodes too. Those are dropped by [isDeclaration], the same filter
 * `call_tree` picks a reference's enclosing declaration with, so every entry emitted here carries a
 * symbol `call_tree` resolves back to exactly one declaration.
 */
internal class OutlineBuilder(private val project: Project, private val basePath: String) {

    private val symbols = DeclarationSymbols()

    /**
     * One declaration on its way into the result, with the index of the entry that encloses it.
     * [label] is read while the structure view model is alive, since it is disposed before the entries
     * are built.
     */
    private class Candidate(val element: PsiElement, val parent: Int, val label: String?)

    /** One rendered declaration, held until the whole file is known so ambiguity can be resolved. */
    private class Entry(
        val symbol: String,
        val signature: String,
        val startLine: Int,
        val endLine: Int,
        val depth: Int,
    )

    suspend fun run(relativePath: String): String {
        val vFile = SourceFiles.find(basePath, relativePath)
            ?: mcpFail("File '$relativePath' not found under the project. Pass a project-relative path.")
        val staleness = SourceFiles.stalenessOf(vFile, relativePath)
        return smartReadAction(project) {
            val psiFile = PsiManager.getInstance(project).findFile(vFile)
                ?: mcpFail("'$relativePath' has no PSI — it is binary or the IDE does not parse this file type.")
            outline(psiFile, relativePath, staleness)
        }
    }

    private fun outline(psiFile: PsiFile, relativePath: String, staleness: Staleness): String {
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
        val candidates = candidatesOf(psiFile)

        val entries = mutableListOf<Entry>()
        var budget = MAX_OUTLINE_CHARS
        var omitted = 0
        val depths = HashMap<Int, Int>()
        for ((index, candidate) in candidates.withIndex()) {
            depths[index] = if (candidate.parent == -1) 0 else (depths[candidate.parent] ?: 0) + 1
            val entry = entryFor(candidate, relativePath, psiFile, document, depths.getValue(index)) ?: continue
            budget -= entry.signature.length + entry.symbol.length + ENTRY_OVERHEAD_CHARS
            if (budget < 0) {
                omitted = candidates.size - index
                break
            }
            entries += entry
        }

        val out = mutableListOf(
            "$relativePath (${psiFile.language.id}, ${document?.lineCount ?: 0} lines)",
        )
        if (!staleness.ideCopyMatchesDisk) out += STALE_LINE
        // A seed for call_tree is the leading identifier of the signature. Where that would not resolve
        // — the name repeats in the file, or it needs a #N ordinal — the exact symbol is spelled out in
        // brackets, and THAT is what call_tree must be given.
        val bareNames = DeclarationSymbols.declarationsOf(psiFile)
            .mapNotNull { DeclarationSymbols.qualifiedName(it)?.substringAfterLast('.') }
            .groupingBy { it }.eachCount()
        for (entry in entries) {
            val last = entry.symbol.substringAfterLast('.')
            val needsSymbol = '#' in entry.symbol || (bareNames[last] ?: 0) > 1
            val range = if (entry.startLine == entry.endLine) "${entry.startLine}" else "${entry.startLine}-${entry.endLine}"
            out += "  ".repeat(entry.depth) + range + " " + entry.signature +
                if (needsSymbol) "  [${entry.symbol}]" else ""
        }
        if (omitted > 0) out += "... $omitted more not listed (output cap) — read the file for the rest"
        return out.joinToString("\n")
    }

    /**
     * The file's declarations in the structure view's own order, flattened depth-first, each carrying
     * the index of the enclosing entry so the renderer can indent it.
     *
     * Depth-first order is what makes the cap safe: a parent always precedes its children, so keeping a
     * prefix of this list never indents an entry under a parent that was dropped.
     */
    private fun candidatesOf(psiFile: PsiFile): List<Candidate> {
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(psiFile)
        if (builder !is TreeBasedStructureViewBuilder) {
            mcpFail(
                "The IDE contributes no structure view for ${psiFile.language.displayName}, so the " +
                    "declarations in '${psiFile.name}' cannot be listed. Read the file instead.",
            )
        }
        val out = mutableListOf<Candidate>()
        val model = builder.createStructureViewModel(null)
        try {
            fun walk(node: TreeElement, parent: Int) {
                val value = (node as? StructureViewTreeElement)?.value
                var enclosing = parent
                if (value is PsiElement && isDeclaration(value)) {
                    out += Candidate(value, parent, node.presentation.presentableText)
                    enclosing = out.size - 1
                }
                node.children.forEach { walk(it, enclosing) }
            }
            walk(model.root, -1)
        } finally {
            Disposer.dispose(model)
        }
        return out
    }

    private fun entryFor(
        candidate: Candidate,
        relativePath: String,
        psiFile: PsiFile,
        document: Document?,
        depth: Int,
    ): Entry? {
        val element = candidate.element
        val symbol = symbols.symbolFor(element, relativePath, psiFile) ?: return null
        val range = element.textRange ?: return null
        return Entry(
            symbol = symbol,
            // The structure view's own label for the declaration: a signature where the language has one
            // to give, and the bare name where it does not. Never any of the body.
            signature = candidate.label ?: symbol.substringAfterLast('.'),
            startLine = lineOf(document, range.startOffset),
            endLine = lineOf(document, range.endOffset),
            depth = depth,
        )
    }

    private fun lineOf(document: Document?, offset: Int): Int {
        if (document == null || offset < 0 || offset > document.textLength) return 0
        return document.getLineNumber(offset) + 1
    }

    companion object {
        private const val STALE_LINE =
            "STALE: the IDE has not re-scanned this file since it changed on disk — " +
                "call refresh_changelists, then run this again"

        /**
         * The cap, in characters of signature and symbol text. Across the 321 source files over 4 KB in
         * `skeleton-kotlin-react` the widest outline came to 9 263 characters, so this never binds on
         * ordinary code and only stops the declaration-dense file where an outline would grow past the
         * whole-file read it exists to replace.
         */
        const val MAX_OUTLINE_CHARS = 24 * 1024

        /**
         * What one entry costs beyond its own text: the JSON field names, the line numbers, the braces.
         * Measured against the serialized result, where 203 entries came to 26 684 characters.
         */
        private const val ENTRY_OVERHEAD_CHARS = 70
    }
}
