package dev.kosmio.hikari.claudetab

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import java.io.IOException

/**
 * Deletes, when a project closes, the session records of its Claude tabs that are gone: closed by `/exit`,
 * the tab's ✕, a crash or a kill alike. A record outlives its tab until then, which is harmless, since a
 * record is only ever read through its tab's identity.
 */
class ClaudeTabSweeper : ProjectCloseListener {

    override fun projectClosing(project: Project) {
        // The IDE restores saved tabs only once the terminal tool window first shows. Until then no tab is
        // live, and sweeping would delete the record of every tab that reopens next time.
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
        if (toolWindow?.contentManagerIfCreated == null) return
        val openTabIds = TerminalToolWindowTabsManager.getInstance(project).tabs
            .mapNotNullTo(HashSet()) { it.processOptions.envVariables[ClaudeTabSessions.TAB_ENV] }
        try {
            ClaudeTabSessions.sweep(project, openTabIds)
        } catch (e: IOException) {
            thisLogger().warn("Cannot sweep the Claude tab sessions of ${project.name}", e)
        }
    }
}
