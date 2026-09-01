plugins {
    id("java")
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
    kotlin("plugin.serialization") version "2.3.21"
}

group = "dev.kosmio"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.1.3")                  // unified IntelliJ IDEA distribution (IC/IU merged since 2025.3)
        bundledPlugin("com.intellij.mcpServer")   // bundled since 2025.2 — NOT the marketplace plugin
        // ChangeListManagerEx, IdeaTextPatchBuilder and UnifiedDiffWriter live in the VCS impl module,
        // which is not on the compile classpath by default (only the vcs API interface module is).
        bundledModule("intellij.platform.vcs.impl")
        // LocalRange (the per-hunk type returned by the partial line-status tracker) lives here.
        bundledModule("intellij.platform.vcs.impl.shared")
        // toLightMethods, the Kotlin half of the call_tree override hop. Contributed from an OPTIONAL
        // config file, so this is a compile-time dependency only — the plugin loads without Kotlin.
        bundledPlugin("org.jetbrains.kotlin")
        // PsiMethod.findSuperMethods, which that hop calls on the light methods it gets back.
        bundledPlugin("com.intellij.java")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    // compileOnly to avoid the kotlinx-serialization classloader clash with the platform's copy:
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    // kotlinx-coroutines is provided by the platform at runtime; if a compile import is missing,
    // add it compileOnly matching the platform version — do NOT bundle it.
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"        // 2026.1+; the project accessor resolves to McpCallInfoKt (2026.1-only).
        }
        changeNotes = "Initial version"
    }
    pluginVerification {
        // The verifier reads plugin.xml only, so it cannot see what the optional Kotlin config file
        // declares; this file lists only what follows from that blind spot.
        ignoredProblemsFile = file("verifier-ignored-problems.txt")
    }
}

tasks.withType<JavaCompile> { sourceCompatibility = "21"; targetCompatibility = "21" }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
