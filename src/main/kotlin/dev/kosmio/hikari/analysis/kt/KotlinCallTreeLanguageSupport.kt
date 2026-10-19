package dev.kosmio.hikari.analysis.kt

import com.intellij.lang.Language
import com.intellij.psi.PsiElement
import dev.kosmio.hikari.analysis.CallTreeLanguageSupport
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Kotlin's call-tree support.
 *
 * The override hop maps a declaration to the light methods the compiler would generate for it, and asks
 * each of those for its super methods. `navigationElement` takes the result back to the source
 * declaration — the Kotlin interface member for a Kotlin port, the `PsiMethod` itself for a Java one.
 *
 * Local properties are what folds, as in the IDE's own Call Hierarchy (`isCallHierarchyElement`): a local
 * named function stays a node of its own, since it is called rather than evaluated in place.
 *
 * Registered only from the optional Kotlin config file, so this class is loaded only in an IDE that has
 * the Kotlin plugin; `toLightMethods` and `PsiMethod` come from plugins the main descriptor does not
 * require.
 */
class KotlinCallTreeLanguageSupport : CallTreeLanguageSupport {

    override val id: String = "kotlin"

    override fun supports(language: Language): Boolean = language.id == KOTLIN_LANGUAGE_ID

    override fun superDeclarations(element: PsiElement): List<PsiElement> =
        element.toLightMethods()
            .flatMap { it.findSuperMethods().asList() }
            .map { it.navigationElement }

    override fun isLocal(element: PsiElement): Boolean = element is KtProperty && element.isLocal

    private companion object {
        const val KOTLIN_LANGUAGE_ID = "kotlin"
    }
}
