import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("java")
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
    kotlin("plugin.serialization") version "2.4.21"
}

group = "dev.kosmio"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdea("263.7031.23")               // 2026.3 EAP; unified IntelliJ IDEA distribution (IC/IU merged since 2025.3)
        bundledPlugin("com.intellij.mcpServer")   // bundled since 2025.2 — NOT the marketplace plugin
        // GitWorkingTreesService, GitWorkingTree and the git command runner behind Merge and Remove.
        bundledPlugin("Git4Idea")
        // com.intellij.dvcs.repo.Repository, GitRepository's supertype, which supplies its root and update().
        bundledModule("intellij.platform.vcs.dvcs")
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
        // The Claude Code tab button and its session-resuming customizer, contributed from an OPTIONAL config file.
        bundledPlugin("org.jetbrains.plugins.terminal")
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
            sinceBuild = "262"        // 2026.2+; GitWorkingTreesService.deleteWorkingTree exists only from 2026.2.
        }
        changeNotes = "Initial version"
    }
    pluginVerification {
        // The verifier reads plugin.xml only, so it cannot see what the optional Kotlin config file
        // declares; this file lists only what follows from that blind spot.
        ignoredProblemsFile = file("verifier-ignored-problems.txt")
        // The default also fails on internal API usage, which the changelist tools cannot avoid:
        // ChangeListManagerImpl, internal from 2026.3, is the only home of editComment.
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
        )
    }
}

intellijPlatformTesting {
    runIde {
        // A sandbox on the oldest supported line, where the code bridging 2026.2 and 2026.3 takes its 2026.2
        // path. `runIde` runs the build target.
        register("runIde262") { version = "2026.2.3" }
    }
}

// 2026.2 and 2026.3 run on JBR 25, and the platform Gradle plugin sets Kotlin's jvmTarget to it on every compile task.
kotlin {
    jvmToolchain(25)
    // 2026.3 adds default methods to McpToolset. The default mode compiles a bridge to each into every
    // toolset, and those bridges reference methods 2026.2 lacks; this mode inherits the defaults instead.
    compilerOptions { jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY) }
}
