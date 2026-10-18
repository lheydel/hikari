package dev.kosmio.hikari.worktree

import com.intellij.ide.impl.ProjectUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.platform.ide.progress.withBackgroundProgress
import git4idea.GitWorkingTree
import git4idea.commands.GitCommand
import git4idea.commands.GitCommandResult
import git4idea.repo.GitRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** What "Merge and Remove" acts on: a linked worktree, and the window whose branch it merges into. */
internal data class MergeRequest(
    val tree: GitWorkingTree,
    val treeBranch: String,
    val repository: GitRepository,
    val targetBranch: String,
) {
    val treePath: Path = Path.of(tree.path.path)
    val treeName: String = treePath.fileName.toString()
}

/**
 * Merges a finished worktree into the current branch of the window the action ran in, then removes the
 * worktree and its branch.
 *
 * Each step is a stop point: when one fails it changes nothing further and says why, and running the
 * action again restarts from the first step, which is a no-op on whatever an earlier run already reached.
 * The merge goes into the worktree first, so conflicts are resolved there by whoever works in it, and the
 * window's branch then only fast-forwards: commits keep their SHAs.
 */
@Service(Service.Level.PROJECT)
internal class WorktreeMergeService(private val project: Project, private val scope: CoroutineScope) {

    fun mergeAndRemove(request: MergeRequest) {
        scope.launch {
            withBackgroundProgress(project, "Merging ${request.treeBranch} into ${request.targetBranch}") {
                val stop = withContext(Dispatchers.IO) { runSteps(request) }
                if (stop == null) {
                    notify(
                        NotificationType.INFORMATION,
                        "Merged ${request.treeBranch} into ${request.targetBranch}, and removed its worktree.",
                    )
                } else {
                    notify(stop.type, stop.message, if (stop.offerOpenTree) request.treePath else null)
                }
            }
        }
    }

    private suspend fun runSteps(request: MergeRequest): Stop? =
        ensureClean(request)
            ?: mergeTargetIntoTree(request)
            ?: fastForwardTarget(request)
            ?: unlinkWorkDirectory(request)
            ?: removeTreeAndBranch(request)
            ?: deleteLeftoverFolder(request)

    private fun ensureClean(request: MergeRequest): Stop? {
        val status = git(request.treePath, GitCommand.STATUS, "--porcelain")
        if (!status.success()) return failed("git status in ${request.treeName}", status)
        if (status.output.any { it.isNotBlank() }) {
            return Stop(
                "${request.treeName} has uncommitted changes. Commit or discard them there, then run Merge and Remove again.",
                offerOpenTree = true,
            )
        }
        return null
    }

    /** Leaves a conflicting merge in progress: never `--abort`, the session in that worktree resolves it. */
    private fun mergeTargetIntoTree(request: MergeRequest): Stop? {
        val merge = git(request.treePath, GitCommand.MERGE, "--no-edit", request.targetBranch)
        if (merge.success()) return null
        val status = git(request.treePath, GitCommand.STATUS, "--porcelain")
        if (status.output.any { it.take(2) in UNMERGED_STATUSES }) {
            return Stop(
                "Merging ${request.targetBranch} into ${request.treeName} conflicts. Resolve the conflicts and " +
                    "commit there, then run Merge and Remove again.",
                offerOpenTree = true,
            )
        }
        return failed("git merge ${request.targetBranch} in ${request.treeName}", merge)
    }

    /** Refreshes afterwards: without it, editors and changelists keep showing the pre-merge state. */
    private fun fastForwardTarget(request: MergeRequest): Stop? {
        val root = request.repository.root
        val merge = git(root.toNioPath(), GitCommand.MERGE, "--ff-only", request.treeBranch)
        if (!merge.success()) return failed("git merge --ff-only ${request.treeBranch}", merge)
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        request.repository.update()
        VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
        return null
    }

    /**
     * Removing the worktree deletes everything under it, so a `.work` that is a link (a symlink, or a
     * junction on Windows) is deleted first, as a link, and a `.work` that is anything else stops the flow:
     * it holds what a session wrote there. `Files.delete` removes a link without following it, and fails
     * on a non-empty directory, so a misdetected directory fails rather than empties.
     */
    private fun unlinkWorkDirectory(request: MergeRequest): Stop? {
        val work = request.treePath.resolve(".work")
        val attributes = try {
            Files.readAttributes(work, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return null
        }
        if (!attributes.isSymbolicLink && !attributes.isOther) {
            return Stop(
                "${request.treeName}/.work is not a link, and removing the worktree would delete it. Move it " +
                    "out, then run Merge and Remove again.",
                offerOpenTree = true,
            )
        }
        Files.delete(work)
        return null
    }

    /** `deleteWorkingTree` returns before git has removed the worktree, so the branch waits on the listing. */
    private suspend fun removeTreeAndBranch(request: MergeRequest): Stop? {
        WorkingTreesCompat.deleteWorkingTree(project, request.tree, request.repository)
        val root = request.repository.root.toNioPath()
        val removed = withTimeoutOrNull(REMOVAL_TIMEOUT_MS) {
            while (isListedWorktree(root, request.treePath)) delay(REMOVAL_POLL_MS)
            true
        }
        if (removed == null) {
            return Stop(
                "${request.treeName} is still a worktree, so its branch ${request.treeBranch} was kept. Once the " +
                    "worktree is removed, run git branch -d ${request.treeBranch}.",
            )
        }
        val delete = git(root, GitCommand.BRANCH, "-d", request.treeBranch)
        return if (delete.success()) null else failed("git branch -d ${request.treeBranch}", delete)
    }

    /**
     * `deleteWorkingTree` closes the worktree's window and removes the worktree right after, while processes
     * that window ran can still hold the folder as their working directory. Git then empties the folder, fails
     * to delete it, and forgets the worktree regardless, so the empty folder is retried here until they exit.
     * A folder that still holds files is left alone: git removed every file it could.
     */
    private suspend fun deleteLeftoverFolder(request: MergeRequest): Stop? {
        repeat(LEFTOVER_ATTEMPTS) {
            try {
                Files.deleteIfExists(request.treePath)
                return null
            } catch (_: DirectoryNotEmptyException) {
                return Stop("${request.treeName} is removed, but git left files in ${request.treePath}. Delete the folder by hand.")
            } catch (_: FileSystemException) {
                delay(REMOVAL_POLL_MS)
            }
        }
        return Stop(
            "${request.treeName} is removed, but another process still holds its folder ${request.treePath}. " +
                "Delete the folder once that process exits.",
        )
    }

    private fun isListedWorktree(root: Path, treePath: Path): Boolean {
        val list = git(root, GitCommand.WORKTREE, "list", "--porcelain")
        return list.output.any {
            it.startsWith(WORKTREE_LINE_PREFIX) &&
                FileUtil.pathsEqual(FileUtil.toSystemIndependentName(it.removePrefix(WORKTREE_LINE_PREFIX)),
                    FileUtil.toSystemIndependentName(treePath.toString()))
        }
    }

    private fun git(directory: Path, command: GitCommand, vararg parameters: String): GitCommandResult =
        WorktreeGit.run(project, directory, command, *parameters)

    private fun failed(what: String, result: GitCommandResult): Stop =
        Stop("$what failed: ${result.errorOutputAsJoinedString.ifBlank { result.outputAsJoinedString }}",
            type = NotificationType.ERROR)

    private fun notify(type: NotificationType, message: String, treeToOpen: Path? = null) {
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("Merge and Remove", message, type)
        if (treeToOpen != null) {
            notification.addAction(NotificationAction.createSimpleExpiring("Open ${treeToOpen.fileName}") {
                bringToFront(treeToOpen)
            })
        }
        notification.notify(project)
    }

    /** Focuses the worktree's window, opening it when no window holds it. */
    private fun bringToFront(treePath: Path) {
        val open = ProjectManager.getInstance().openProjects.firstOrNull {
            it.basePath != null && FileUtil.pathsEqual(it.basePath, FileUtil.toSystemIndependentName(treePath.toString()))
        }
        if (open != null) ProjectUtil.focusProjectWindow(open, true) else ProjectUtil.openOrImport(treePath, null, true)
    }

    private data class Stop(
        val message: String,
        val type: NotificationType = NotificationType.WARNING,
        val offerOpenTree: Boolean = false,
    )

    private companion object {
        const val NOTIFICATION_GROUP = "hikari.worktree"
        const val WORKTREE_LINE_PREFIX = "worktree "

        /** The two-letter `git status --porcelain` codes of an unmerged path. */
        val UNMERGED_STATUSES = setOf("DD", "AU", "UD", "UA", "DU", "AA", "UU")

        /** Long enough for the user to answer the prompt `deleteWorkingTree` raises over a running terminal. */
        const val REMOVAL_TIMEOUT_MS = 120_000L
        const val REMOVAL_POLL_MS = 500L

        /** At [REMOVAL_POLL_MS] apart, half a minute for the closed window's processes to exit. */
        const val LEFTOVER_ATTEMPTS = 60
    }
}
