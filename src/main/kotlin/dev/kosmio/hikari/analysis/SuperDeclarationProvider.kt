package dev.kosmio.hikari.analysis

import com.intellij.lang.Language
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiElement

/**
 * The "what does this declaration override" hop, which has no language-neutral platform API — the
 * searches the traversal is otherwise built from ([com.intellij.psi.search.searches.ReferencesSearch]
 * and `DefinitionsScopedSearch`) run downward, from a declaration to its implementations.
 *
 * It is the hop that makes the traversal reach anything at all for an implementation whose only
 * reference is through the port it overrides. Implementations are contributed from optional config
 * files, so an IDE without the language plugin loads the rest of this plugin unaffected; a language
 * with no registered provider is reported to the caller as *hop unavailable*, never as zero callers.
 */
interface SuperDeclarationProvider {

    /** Short identifier reported in the result's `superHop.providers`. */
    val id: String

    /** Whether this provider handles [language]. */
    fun supports(language: Language): Boolean

    /**
     * The declarations [element] overrides or implements, as source elements. Called under a read
     * action; returns an empty list for an element it does not handle.
     */
    fun superDeclarations(element: PsiElement): List<PsiElement>

    companion object {
        val EP_NAME: ExtensionPointName<SuperDeclarationProvider> =
            ExtensionPointName.create("dev.kosmio.hikari.superDeclarationProvider")
    }
}
