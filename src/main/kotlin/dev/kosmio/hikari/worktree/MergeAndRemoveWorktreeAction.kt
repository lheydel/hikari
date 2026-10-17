package dev.kosmio.hikari.worktree

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction

/**
 * "Merge and Remove" in the Worktrees tab: merges the selected worktree into the current branch of this
 * window, then removes the worktree and its branch ([WorktreeMergeService]).
 *
 * Enabled for exactly one selected worktree that is neither the main one nor this window's own, with both
 * it and this window on a branch.
 */
class MergeAndRemoveWorktreeAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null && requestOf(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val request = requestOf(e) ?: return
        project.service<WorktreeMergeService>().mergeAndRemove(request)
    }

    private fun requestOf(e: AnActionEvent): MergeRequest? {
        val tree = e.getData(WorkingTreesCompat.SELECTED_WORKING_TREES)?.singleOrNull() ?: return null
        if (tree.isMain || tree.isCurrent) return null
        val treeBranch = tree.currentBranch?.name ?: return null
        val repository = e.getData(WorkingTreesCompat.CURRENT_REPOSITORY) ?: return null
        val targetBranch = repository.currentBranch?.name ?: return null
        return MergeRequest(tree, treeBranch, repository, targetBranch)
    }
}
