package dev.kosmio.hikari.mcproxy

import com.intellij.mcpserver.impl.McpServerService
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.dsl.builder.panel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel

/**
 * Tools › MCP Server › mcproxy: whether this IDE's MCP URL is listed in mcproxy's config, and a button that
 * lists it ([McproxyConfig]). The button writes at once, as the IDE's own client auto-configuration does, so
 * the page has nothing for Apply or Cancel to act on.
 */
class McproxyConfigurable : Configurable {

    private val urlLabel = JLabel()
    private val statusLabel = JLabel()
    private lateinit var addButton: JButton
    private var pending: Job? = null

    override fun getDisplayName(): String = "mcproxy"

    override fun createComponent(): JComponent = panel {
        row("Config file:") { label(McproxyConfigFile.path.toString()) }
        row("This IDE:") { cell(urlLabel) }
        row("Status:") { cell(statusLabel) }
        row { addButton = button("Add to mcproxy") { refresh(add = true) }.component }
    }

    override fun isModified(): Boolean = false

    override fun apply() {}

    override fun reset() = refresh(add = false)

    override fun disposeUIResources() {
        // cancel(null), not cancel(): the default-argument bridge cancel() compiles to is absent from 2026.2's coroutines.
        pending?.cancel(null)
    }

    /** Re-reads the config, after listing this IDE in it when [add] is set. */
    private fun refresh(add: Boolean) {
        val server = McpServerService.getInstance()
        addButton.isEnabled = server.isRunning
        if (!server.isRunning) {
            urlLabel.text = "none: the MCP server is off; enable it on the MCP Server page"
            statusLabel.text = "unknown while the MCP server is off"
            return
        }
        val url = server.serverStreamUrl
        val product = ApplicationNamesInfo.getInstance().fullProductName
        urlLabel.text = url
        pending?.cancel(null)
        pending = service<McproxyPageScope>().scope.launch(ModalityState.current().asContextElement()) {
            val status = withContext(Dispatchers.IO) {
                try {
                    describe(if (add) addAndRead(url, product) else read(url, product))
                } catch (e: IOException) {
                    "Cannot ${if (add) "write" else "read"} ${McproxyConfigFile.path}: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            withContext(Dispatchers.EDT) { statusLabel.text = html(status) }
        }
    }

    private class Shown(val exists: Boolean, val reading: McproxyConfig.Reading)

    private fun read(url: String, product: String): Shown {
        val text = McproxyConfigFile.read()
        return Shown(text != null, McproxyConfig.read(text, url, product))
    }

    private fun addAndRead(url: String, product: String): Shown {
        val text = McproxyConfigFile.read()
        when (val edit = McproxyConfig.add(text, url, product)) {
            is McproxyConfig.Edit.Refused -> return Shown(text != null, McproxyConfig.Reading.Refused(edit.message))
            is McproxyConfig.Edit.Written -> if (edit.text != text) McproxyConfigFile.write(edit.text)
        }
        return read(url, product)
    }

    private fun describe(shown: Shown): String =
        when (val reading = shown.reading) {
            is McproxyConfig.Reading.Refused -> reading.message
            is McproxyConfig.Reading.Groups -> when {
                reading.statuses.isNotEmpty() -> reading.statuses.joinToString("\n") { (group, status) ->
                    "$group: ${describe(status)}"
                }
                shown.exists -> "no group has adapter \"jetbrains\"; Add to mcproxy adds group ${McproxyConfig.DEFAULT_GROUP}"
                else -> "no config file; Add to mcproxy creates it with group ${McproxyConfig.DEFAULT_GROUP}"
            }
        }

    private fun describe(status: McproxyConfig.Status): String =
        when (status) {
            McproxyConfig.Status.Listed -> "listed"
            is McproxyConfig.Status.ListedWithAnotherUrl -> "listed with another URL, ${status.url}"
            McproxyConfig.Status.ListedUntidy -> "listed, but not as a single line commented with this IDE's name"
            McproxyConfig.Status.NotListed -> "not listed"
        }

    private fun html(text: String): String =
        "<html>" + StringUtil.escapeXmlEntities(text).replace("\n", "<br>").replace("  ", "&nbsp;&nbsp;") + "</html>"
}

/** The scope the mcproxy page reads and writes the config in, off the EDT. */
@Service(Service.Level.APP)
internal class McproxyPageScope(val scope: CoroutineScope)
