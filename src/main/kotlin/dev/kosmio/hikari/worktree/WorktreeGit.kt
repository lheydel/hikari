package dev.kosmio.hikari.worktree

import com.intellij.openapi.project.Project
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitCommandResult
import git4idea.commands.GitLineHandler
import java.nio.file.Path

/**
 * Runs git through git4idea rather than a raw process, so a command uses the git executable and the
 * environment the IDE is configured with. Blocking: call it off the EDT.
 */
internal object WorktreeGit {

    fun run(project: Project, directory: Path, command: GitCommand, vararg parameters: String): GitCommandResult {
        val handler = GitLineHandler(project, directory.toFile(), command)
        handler.addParameters(*parameters)
        return Git.getInstance().runCommand(handler)
    }
}
