package dev.kosmio.hikari.analysis

import com.intellij.lang.Language
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiElement

/**
 * What the analysis tools need to know about a language and the platform has no language-neutral API
 * for: what a declaration overrides, which declarations are local to the one enclosing them, and which
 * declarations are named by a child rather than being a [com.intellij.psi.PsiNameIdentifierOwner]
 * themselves.
 *
 * The override hop is what makes the traversal reach anything at all for an implementation whose only
 * reference is through the port it overrides — the searches the traversal is otherwise built from
 * ([com.intellij.psi.search.searches.ReferencesSearch] and `DefinitionsScopedSearch`) run downward, from a
 * declaration to its implementations. Implementations that need a language plugin's classes are
 * contributed from optional config files, so an IDE without the language plugin loads the rest of this
 * plugin unaffected; a language with no support answering the hop is reported to the caller as *hop
 * unavailable*, never as zero callers.
 */
interface CallTreeLanguageSupport {

    /** Short identifier reported on the result's override-hop line. */
    val id: String

    /**
     * Whether [superDeclarations] answers for this language. A support contributing only the other
     * answers says false, so the result reports the hop unavailable rather than nothing overridden.
     */
    val answersOverrides: Boolean get() = true

    /** Whether this support handles [language]. */
    fun supports(language: Language): Boolean

    /**
     * The declarations [element] overrides or implements, as source elements. Called under a read
     * action; returns an empty list for an element it does not handle.
     */
    fun superDeclarations(element: PsiElement): List<PsiElement>

    /**
     * Whether [element] is a declaration local to the one enclosing it, which the tree folds into that
     * enclosing declaration: a reference inside a local variable's initializer is reported as a call from
     * the function holding the variable. Called under a read action.
     */
    fun isLocal(element: PsiElement): Boolean

    /**
     * The child naming [element] where the language makes that name node, not the declaration, the
     * [com.intellij.psi.PsiNameIdentifierOwner] references resolve to; null for any other element.
     * Called under a read action.
     */
    fun nameNodeOf(element: PsiElement): PsiElement? = null

    companion object {
        val EP_NAME: ExtensionPointName<CallTreeLanguageSupport> =
            ExtensionPointName.create("dev.kosmio.hikari.callTreeLanguageSupport")

        /** The supports registered for [language]. */
        fun of(language: Language): List<CallTreeLanguageSupport> =
            EP_NAME.extensionList.filter { it.supports(language) }
    }
}
