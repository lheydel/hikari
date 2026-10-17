package dev.kosmio.hikari.worktree

import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import git4idea.GitWorkingTree
import git4idea.repo.GitRepository
import git4idea.workingTrees.GitWorkingTreesService

/**
 * The parts of git4idea's worktree support whose binary form differs between 2026.2 and 2026.3, reached
 * in a way one build can use on both.
 *
 * TODO: once `sinceBuild` is 263 or later, read the keys from
 *  `git4idea.workingTrees.ui.actions.GitWorkingTreeTabActionsDataKeys`, call
 *  `GitWorkingTreesService.deleteWorkingTree` directly, and delete this object.
 */
internal object WorkingTreesCompat {

    /**
     * The rows selected in the Worktrees tab. The keys' holder class moved package in 2026.3, but a
     * `DataKey` is identified by its name, which both versions share.
     */
    val SELECTED_WORKING_TREES: DataKey<List<GitWorkingTree>> =
        DataKey.create("SELECTED_GIT_WORKING_TREE_IN_WORKING_TREE_TAB")

    /** The repository of the window the Worktrees tab belongs to. */
    val CURRENT_REPOSITORY: DataKey<GitRepository> = DataKey.create("CURRENT_REPOSITORY_IN_WORKING_TREE_TAB")

    /**
     * `GitWorkingTreesService.deleteWorkingTree`, which closes the worktree's window when it is open and
     * then removes the worktree. It returns `void` on 2026.2 and a `Job` on 2026.3, so a compiled call
     * binds one return type and throws `NoSuchMethodError` on the other platform; reflection resolves it
     * by its parameters alone. It returns before the removal finishes.
     */
    fun deleteWorkingTree(project: Project, tree: GitWorkingTree, repository: GitRepository) {
        val method = GitWorkingTreesService::class.java.getMethod(
            "deleteWorkingTree", Project::class.java, GitWorkingTree::class.java, GitRepository::class.java,
        )
        method.invoke(project.service<GitWorkingTreesService>(), project, tree, repository)
    }
}
