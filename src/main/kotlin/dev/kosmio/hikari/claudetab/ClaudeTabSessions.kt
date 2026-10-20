package dev.kosmio.hikari.claudetab

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.UUID

/**
 * Which Claude Code session runs in each hikari Claude tab. A hikari tab carries a random UUID in its
 * saved environment ([TAB_ENV]); the session it runs is recorded in one file per tab, named after that
 * UUID, in a directory per project under the IDE's system directory. The file holds Claude Code's raw
 * `SessionStart` hook input, written by the hook [ClaudeTabCustomizer] injects.
 */
internal object ClaudeTabSessions {

    /** The tab's identity, saved with the tab and handed back when the IDE reopens it. */
    const val TAB_ENV = "HIKARI_CLAUDE_TAB"

    /** The tab's mapping file, set in the process environment on every start; never saved with the tab. */
    const val FILE_ENV = "HIKARI_CLAUDE_TAB_FILE"

    private val json = Json { ignoreUnknownKeys = true }

    private val root: Path get() = PathManager.getSystemDir().resolve("hikari").resolve("claude-tabs")

    fun directory(project: Project): Path = root.resolve(project.locationHash)

    /** The `--settings` file every hikari tab's `claude` is started with; shared by every project. */
    val settingsFile: Path get() = root.resolve("settings.json")

    /** The mapping file of the tab whose identity is [tabId], or null when [tabId] is not a UUID. */
    fun file(project: Project, tabId: String): Path? {
        val uuid = try {
            UUID.fromString(tabId)
        } catch (_: IllegalArgumentException) {
            return null
        }
        // fromString accepts non-canonical forms; only the canonical one is a safe file name.
        if (uuid.toString() != tabId) return null
        return directory(project).resolve(tabId)
    }

    /**
     * The session recorded in [file] when Claude Code can still resume it. A file that cannot be read as
     * a session, or whose transcript is gone, is deleted: a session Claude Code has cleaned up must never
     * reach `--resume`, which would end the tab on startup.
     */
    fun resumableSession(file: Path): String? {
        if (Files.notExists(file)) return null
        val session = read(file)
        if (session != null && isUuid(session.sessionId) && transcriptExists(session)) return session.sessionId
        Files.deleteIfExists(file)
        return null
    }

    /**
     * Deletes every record of [project] whose tab is not in [openTabIds], and every one whose session can no
     * longer resume.
     */
    fun sweep(project: Project, openTabIds: Set<String>) {
        val directory = directory(project)
        if (Files.notExists(directory)) return
        for (file in Files.list(directory).use { it.toList() }) {
            if (file.fileName.toString() in openTabIds) resumableSession(file) else Files.deleteIfExists(file)
        }
    }

    private fun read(file: Path): HookInput? =
        try {
            json.decodeFromString<HookInput>(Files.readString(file))
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun transcriptExists(session: HookInput): Boolean =
        try {
            Files.isRegularFile(Path.of(session.transcriptPath))
        } catch (_: InvalidPathException) {
            false
        }

    private fun isUuid(value: String): Boolean =
        try {
            UUID.fromString(value).toString() == value.lowercase()
        } catch (_: IllegalArgumentException) {
            false
        }

    /** The fields of Claude Code's `SessionStart` hook input this feature reads. */
    @Serializable
    private data class HookInput(
        @SerialName("session_id") val sessionId: String,
        @SerialName("transcript_path") val transcriptPath: String,
    )
}
