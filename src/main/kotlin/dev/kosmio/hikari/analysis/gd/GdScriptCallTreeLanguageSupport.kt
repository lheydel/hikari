package dev.kosmio.hikari.analysis.gd

import com.intellij.lang.Language
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.StubBasedPsiElement
import dev.kosmio.hikari.analysis.CallTreeLanguageSupport
import dev.kosmio.hikari.analysis.DeclarationSymbols

/**
 * GDScript's call-tree support: the name node of each declaration.
 *
 * GDScript makes a declaration's name child the [PsiNameIdentifierOwner] — `GdMethodDeclTl` holds a
 * `GdMethodIdNmi`, and references resolve to that child — so no declaration is an ancestor of the code
 * inside it. A name node is the only declaration among a stub-indexed element's children and spans
 * nothing but its name. The stub requirement keeps it to the top-level declarations a symbol can name
 * from outside: local variables, loop variables and parameters are not stubs, so a call in their
 * initializer is reported from the function holding them.
 *
 * It answers no override hop. It uses no GDScript class, so it is registered from the main descriptor
 * and loads in an IDE without the GDScript plugin, where it never matches a language.
 */
class GdScriptCallTreeLanguageSupport : CallTreeLanguageSupport {

    override val id: String = "gdscript"

    override val answersOverrides: Boolean = false

    override fun supports(language: Language): Boolean = language.id == GDSCRIPT_LANGUAGE_ID

    override fun superDeclarations(element: PsiElement): List<PsiElement> = emptyList()

    override fun isLocal(element: PsiElement): Boolean = false

    override fun nameNodeOf(element: PsiElement): PsiElement? {
        if (element !is StubBasedPsiElement<*>) return null
        val named = element.children.filter(DeclarationSymbols::isDeclaration).singleOrNull()
            as? PsiNameIdentifierOwner ?: return null
        return named.takeIf { it.nameIdentifier?.textRange == it.textRange }
    }

    private companion object {
        const val GDSCRIPT_LANGUAGE_ID = "GdScript"
    }
}
