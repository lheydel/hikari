package dev.kosmio.hikari.worktree

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import git4idea.commands.GitCommand
import git4idea.commands.GitCommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Copies into a linked worktree the files of the main worktree that are untracked, gitignored and listed
 * by its `.worktreeinclude`: the set `claude -w` copies, so a worktree the IDE created holds them too.
 * Runs on every open of a project rooted at a linked worktree and copies only what the worktree lacks, so
 * an entry added to `.worktreeinclude` later still arrives and nothing is overwritten. Silent unless a
 * copy fails.
 *
 * 2026.3 copies `.worktreeinclude` files itself when it creates a worktree, so this runs on 2026.2 only.
 *
 * TODO: once `sinceBuild` is 263 or later, delete this activity and its registration.
 */
class WorktreeIncludeActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (ApplicationInfo.getInstance().build.baselineVersion >= NATIVE_COPY_BASELINE) return
        val basePath = project.basePath ?: return
        val failure = withContext(Dispatchers.IO) {
            try {
                copyMissingIncludes(project, Path.of(basePath))
            } catch (e: IOException) {
                e.message ?: e.toString()
            }
        }
        if (failure != null) {
            NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification("Worktree include", failure, NotificationType.WARNING)
                .notify(project)
        }
    }

    /** Returns what failed, or null; a directory outside git, or a main worktree, is no failure. */
    private fun copyMissingIncludes(project: Project, directory: Path): String? {
        val layout = WorktreeGit.run(
            project, directory, GitCommand.REV_PARSE,
            "--path-format=absolute", "--show-toplevel", "--git-dir", "--git-common-dir",
        )
        if (!layout.success() || layout.output.size < 3) return null
        val (tree, gitDir, commonDir) = layout.output.take(3).map { Path.of(it).normalize() }
        if (gitDir == commonDir) return null
        val main = commonDir.parent
        if (Files.notExists(main.resolve(INCLUDE_FILE))) return null

        val listed = WorktreeGit.run(
            project, main, GitCommand.LS_FILES, "-z", "--others", "--ignored", "--exclude-from=$INCLUDE_FILE",
        )
        if (!listed.success()) return "git ls-files in ${main.fileName} failed: ${listed.errorOutputAsJoinedString}"
        val missing = paths(listed).filter { Files.notExists(tree.resolve(it), LinkOption.NOFOLLOW_LINKS) }
        if (missing.isEmpty()) return null

        // Which of them the standard ignore rules also cover; `ls-files -z` names them unquoted, which
        // `check-ignore` does only when reading its paths from stdin.
        val ignored = mutableListOf<String>()
        for (batch in missing.chunked(PATHSPEC_BATCH)) {
            val check = WorktreeGit.run(
                project, main, GitCommand.LS_FILES, "-z", "--others", "--ignored", "--exclude-standard", "--",
                *batch.map { ":(literal)$it" }.toTypedArray(),
            )
            if (!check.success()) return "git ls-files in ${main.fileName} failed: ${check.errorOutputAsJoinedString}"
            ignored += paths(check)
        }

        val copied = mutableListOf<Path>()
        val failed = mutableListOf<String>()
        for (relative in ignored) {
            val target = tree.resolve(relative)
            try {
                Files.createDirectories(target.parent)
                Files.copy(main.resolve(relative), target, StandardCopyOption.COPY_ATTRIBUTES)
                copied.add(target)
            } catch (e: IOException) {
                failed += "$relative (${e.message})"
            }
        }
        if (copied.isNotEmpty()) LocalFileSystem.getInstance().refreshNioFiles(copied)
        return if (failed.isEmpty()) null
        else "Could not copy from ${main.fileName}'s .worktreeinclude: ${failed.joinToString(", ")}"
    }

    /** The NUL-separated paths of a `-z` git command. */
    private fun paths(result: GitCommandResult): List<String> =
        result.outputAsJoinedString.split('\u0000').filter { it.isNotBlank() }.map { it.trim('\n', '\r') }

    private companion object {
        const val INCLUDE_FILE = ".worktreeinclude"
        const val NOTIFICATION_GROUP = "hikari.worktree"

        /** The first platform whose own worktree creation copies `.worktreeinclude` files. */
        const val NATIVE_COPY_BASELINE = 263

        /** Paths per `git ls-files` call, keeping its command line well inside Windows' limit. */
        const val PATHSPEC_BATCH = 100
    }
}
