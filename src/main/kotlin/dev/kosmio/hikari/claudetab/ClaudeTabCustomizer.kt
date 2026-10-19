package dev.kosmio.hikari.claudetab

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.eel.provider.LocalEelDescriptor
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.plugins.terminal.LocalTerminalCustomizer
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Rewrites the command of a hikari Claude tab as its process starts: points [ClaudeTabSessions.FILE_ENV]
 * at the tab's mapping file, injects the hook that records the tab's session there, and resumes the
 * session already recorded. The IDE runs customizers for every process type, so this sees the tab's
 * `claude` process as well as shells; anything without [ClaudeTabSessions.TAB_ENV], or not running
 * `claude`, passes through untouched.
 *
 * Local macOS and Linux only: the hook is a POSIX `sh` command, and the mapping files live on the IDE's
 * host.
 */
class ClaudeTabCustomizer : LocalTerminalCustomizer() {

    override fun customizeCommandAndEnvironment(
        project: Project,
        workingDirectory: String?,
        command: List<String>,
        envs: MutableMap<String, String>,
        eelDescriptor: EelDescriptor,
    ): List<String> {
        val tabId = envs[ClaudeTabSessions.TAB_ENV] ?: return command
        if (eelDescriptor != LocalEelDescriptor || SystemInfo.isWindows || !runsClaude(command)) return command
        val file = ClaudeTabSessions.file(project, tabId) ?: return command
        val session = try {
            Files.createDirectories(file.parent)
            ClaudeTabSessions.resumableSession(file)
        } catch (e: IOException) {
            thisLogger().warn("Cannot read the Claude session of terminal tab $tabId", e)
            null
        }
        envs[ClaudeTabSessions.FILE_ENV] = file.toString()
        val resume = if (session != null) listOf("--resume", session) else emptyList()
        return command + listOf("--settings", HOOK_SETTINGS) + resume
    }

    private fun runsClaude(command: List<String>): Boolean =
        try {
            command.firstOrNull()?.let { Path.of(it).fileName?.toString() } == CLAUDE_BINARY
        } catch (_: InvalidPathException) {
            false
        }

    private companion object {
        const val CLAUDE_BINARY = "claude"

        /**
         * Copies Claude Code's `SessionStart` hook input to the tab's mapping file. Silent and always
         * successful: `SessionStart` stdout is added to Claude's context, and a missing variable or an
         * unwritable path must not disturb the session.
         */
        const val HOOK_COMMAND =
            "[ -n \"\$${ClaudeTabSessions.FILE_ENV}\" ] && { cat > \"\$${ClaudeTabSessions.FILE_ENV}\"; } 2>/dev/null; exit 0"

        /**
         * The `--settings` value. It holds the `hooks` key alone, so it overrides none of the user's
         * settings, and Claude Code runs its hooks alongside the user's own for the same event. The hook
         * travels with the flag rather than the environment, so a `claude` started from inside the session
         * inherits [ClaudeTabSessions.FILE_ENV] but never records over the tab's session.
         */
        val HOOK_SETTINGS: String = buildJsonObject {
            putJsonObject("hooks") {
                putJsonArray("SessionStart") {
                    addJsonObject {
                        putJsonArray("hooks") {
                            addJsonObject {
                                put("type", "command")
                                put("command", HOOK_COMMAND)
                            }
                        }
                    }
                }
            }
        }.toString()
    }
}
