package dev.kosmio.hikari.claudetab

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.SystemInfo

/**
 * "Claude Code" in the terminal tool window's tab strip: opens a Claude Code tab that resumes its session
 * when the project reopens ([ClaudeTabLauncher]).
 *
 * Hidden on Windows, where the tab could not resume. Whether Claude Code is installed is known only once
 * the launch spec resolves, so the launcher answers that one; installing stays with the IDE's own agent
 * selector.
 */
class NewClaudeTabAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && !SystemInfo.isWindows
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<ClaudeTabLauncher>()?.open()
    }
}
