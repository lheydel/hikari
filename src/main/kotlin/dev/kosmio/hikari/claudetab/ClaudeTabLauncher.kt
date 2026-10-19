package dev.kosmio.hikari.claudetab

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.terminal.agent.TerminalAgent
import org.jetbrains.plugins.terminal.agent.TerminalAgentResolver
import org.jetbrains.plugins.terminal.agent.rpc.TerminalAgentMode
import org.jetbrains.plugins.terminal.startup.TerminalProcessType
import java.util.UUID

/**
 * Opens a hikari Claude tab: a terminal tab built as the IDE's own agent launcher builds a Claude Code tab
 * (`TerminalAgentsSupportKt.launchTerminalAgent`), plus a fresh [ClaudeTabSessions.TAB_ENV] in its saved
 * environment. The saved command stays the plain `claude` path, so without hikari the tab reopens as an
 * ordinary new session.
 */
@Service(Service.Level.PROJECT)
internal class ClaudeTabLauncher(private val project: Project, private val scope: CoroutineScope) {

    fun open() {
        scope.launch {
            val spec = TerminalAgentResolver.resolveLaunchSpec(project, CLAUDE_CODE)
            val agent = TerminalAgent.findByKey(CLAUDE_CODE)
            if (spec == null || spec.mode != TerminalAgentMode.RUN || agent == null) {
                NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification(
                        "Claude Code is not installed",
                        "Install it from the terminal's AI agent selector, then open the tab again.",
                        NotificationType.WARNING,
                    )
                    .notify(project)
                return@launch
            }
            withContext(Dispatchers.EDT) {
                if (project.isDisposed) return@withContext
                val tab = TerminalToolWindowTabsManager.getInstance(project).createTabBuilder()
                    .shellCommand(spec.command)
                    .processType(TerminalProcessType.NON_SHELL)
                    .tabName(agent.displayName)
                    .envVariables(mapOf(ClaudeTabSessions.TAB_ENV to UUID.randomUUID().toString()))
                    // The tab closes when `claude` exits, so a session ended on purpose is not reopened.
                    .closeOnProcessTermination(true)
                    .createTab()
                if (agent.showIconInTab) {
                    tab.content.putUserData(ToolWindow.SHOW_CONTENT_ICON, true)
                    tab.content.icon = agent.icon
                }
            }
        }
    }

    companion object {
        val CLAUDE_CODE = TerminalAgent.AgentKey("claude_code")
        const val NOTIFICATION_GROUP = "hikari.claudetab"
    }
}
