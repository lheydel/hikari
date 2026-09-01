package dev.kosmio.mcppowerup.analysis

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Locating the file an analysis tool was asked about, and reporting how fresh the IDE's copy of it is. */
internal object SourceFiles {

    /** The file at a project-relative (or absolute) [path], or null when nothing is there. */
    fun find(basePath: String, path: String): VirtualFile? {
        val absolute = if (path.startsWith("/")) path else "$basePath/$path"
        return LocalFileSystem.getInstance().findFileByPath(absolute)
    }

    /**
     * Whether the IDE's copy of the file still matches what is on disk. An edit written outside the IDE
     * does not reach the VFS until a re-scan, and the answer gives no other sign of it: an analysis runs
     * happily against the file as the IDE last saw it, and returns a confident result built from text
     * that no longer exists.
     */
    fun stalenessOf(vFile: VirtualFile, relativePath: String): Staleness {
        val path = try {
            Path.of(vFile.path)
        } catch (_: InvalidPathException) {
            return Staleness(relativePath, true)
        }
        val matches = try {
            Files.exists(path) && Files.size(path) == vFile.length &&
                Files.getLastModifiedTime(path).toMillis() <= vFile.timeStamp
        } catch (_: IOException) {
            return Staleness(relativePath, true)
        }
        return Staleness(relativePath, matches)
    }

    /** Appended to an error message, so a failure to resolve a symbol names staleness as a likely cause. */
    fun staleSuffix(staleness: Staleness): String =
        if (staleness.ideCopyMatchesDisk) {
            ""
        } else {
            " The IDE has not re-scanned this file since it changed on disk — call refresh_changelists first."
        }
}
