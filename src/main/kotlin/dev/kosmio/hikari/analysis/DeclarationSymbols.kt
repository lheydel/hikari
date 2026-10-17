package dev.kosmio.hikari.analysis

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner

/**
 * The grammar the analysis tools name a declaration with: its name qualified by its enclosing
 * declarations (`ProjectCreateFeatureImpl.invoke`), with `#N` appended — 1-based, document order — when
 * one file holds more than one declaration under that qualified name.
 *
 * `outline` emits it and `call_tree` accepts it verbatim, which is what lets them compose. That holds
 * only while each derives it here: the `#N` ordinal counts *every* declaration in the file, including the
 * local ones a structure view never shows, so a second implementation of the same rule would number
 * differently and the tools would agree on paper and disagree in practice.
 *
 * An instance caches one ordinal index per file, so it is scoped to a single tool call — the index
 * describes the file as it stood when it was built.
 */
internal class DeclarationSymbols {

    /** Per-file `qualified name -> declaration offsets in document order`, what `#N` is counted from. */
    private val ordinalIndexes = HashMap<String, Map<String, List<Int>>>()

    /**
     * [element]'s symbol, or null when it is not a declaration. [relativePath] keys the ordinal index,
     * so it must name the file [psiFile] came from.
     */
    fun symbolFor(element: PsiElement, relativePath: String, psiFile: PsiFile): String? {
        val qualified = qualifiedName(element) ?: return null
        val offsets = ordinalIndexes.getOrPut(relativePath) { indexOrdinals(psiFile) }[qualified]
        if (offsets == null || offsets.size <= 1) return qualified
        val position = offsets.indexOf(element.textOffset)
        return if (position < 0) qualified else "$qualified#${position + 1}"
    }

    private fun indexOrdinals(psiFile: PsiFile): Map<String, List<Int>> {
        val index = HashMap<String, MutableList<Int>>()
        for (declaration in declarationsOf(psiFile)) {
            val qualified = qualifiedName(declaration) ?: continue
            index.getOrPut(qualified) { mutableListOf() } += declaration.textOffset
        }
        return index
    }

    companion object {

        /**
         * Whether an element is a declaration a symbol can name. The platform gives synthetic elements a
         * placeholder name in angle brackets rather than no name at all — a Kotlin lambda answers
         * `<anonymous>`, not null — and those are dead ends: nothing references a lambda, so a tree that
         * stopped at one would report a leaf where the enclosing function is the real caller.
         *
         * [PsiNameIdentifierOwner] is language-neutral and answers the question that actually matters —
         * is this a declaration rather than a file or markup. A [PsiFile] is not one and neither is a JSX
         * literal or an ES6 re-export specifier, so they drop out, while the property declarations, which
         * are real call sites, stay.
         */
        fun isDeclaration(element: PsiElement): Boolean {
            if (element !is PsiNameIdentifierOwner) return false
            val name = element.name ?: return false
            return !name.startsWith("<")
        }

        /**
         * The declaration's name qualified by the enclosing declarations that have real names. The
         * synthetic ones are skipped rather than spelled out, so a property declared inside a lambda
         * reads `UserDeleteFeatureImpl.invoke.deleted` — still unique within the file, with `#N` for
         * what is not.
         */
        fun qualifiedName(element: PsiElement): String? {
            val parts = mutableListOf<String>()
            var current: PsiElement? = element
            while (current != null && current !is PsiFile) {
                if (isDeclaration(current)) parts += (current as PsiNameIdentifierOwner).name.orEmpty()
                current = current.parent
            }
            return if (parts.isEmpty()) null else parts.asReversed().joinToString(".")
        }

        /** Every declaration in the file, in document order. */
        fun declarationsOf(psiFile: PsiFile): List<PsiElement> {
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

        /**
         * The declarations [name] selects: the ones whose qualified name is exactly [name], or — when a
         * caller passed a bare name rather than a qualified one — the ones it is the trailing segment of.
         */
        fun matchesFor(declarations: List<PsiElement>, name: String): List<PsiElement> {
            val exact = declarations.filter { qualifiedName(it) == name }
            if (exact.isNotEmpty()) return exact
            return declarations.filter { qualifiedName(it)?.endsWith(".$name") == true }
        }

        /** Split a symbol into its qualified name and its `#N` ordinal, which is absent for most. */
        fun parseSymbol(symbol: String): Pair<String, Int?> {
            val hash = symbol.lastIndexOf('#')
            if (hash <= 0) return symbol to null
            val ordinal = symbol.substring(hash + 1).toIntOrNull() ?: return symbol to null
            return symbol.substring(0, hash) to ordinal
        }
    }
}
