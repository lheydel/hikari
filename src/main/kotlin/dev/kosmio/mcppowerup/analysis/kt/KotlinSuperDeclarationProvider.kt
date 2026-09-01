package dev.kosmio.mcppowerup.analysis.kt

import com.intellij.lang.Language
import com.intellij.psi.PsiElement
import dev.kosmio.mcppowerup.analysis.SuperDeclarationProvider
import org.jetbrains.kotlin.asJava.toLightMethods

/**
 * The Kotlin override hop: a declaration is mapped to the light methods the compiler would generate for
 * it, and each of those is asked for its super methods. `navigationElement` takes the result back to the
 * source declaration — the Kotlin interface member for a Kotlin port, the `PsiMethod` itself for a Java one.
 *
 * Registered only from the optional Kotlin config file, so this class is loaded only in an IDE that has
 * the Kotlin plugin; `toLightMethods` and `PsiMethod` come from plugins the main descriptor does not
 * require.
 */
class KotlinSuperDeclarationProvider : SuperDeclarationProvider {

    override val id: String = "kotlin"

    override fun supports(language: Language): Boolean = language.id == KOTLIN_LANGUAGE_ID

    override fun superDeclarations(element: PsiElement): List<PsiElement> =
        element.toLightMethods()
            .flatMap { it.findSuperMethods().asList() }
            .map { it.navigationElement }

    private companion object {
        const val KOTLIN_LANGUAGE_ID = "kotlin"
    }
}
