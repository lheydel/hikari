package dev.kosmio.hikari.indexing

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * Tools › Index Exclusions: the patterns excluded from indexing in every project, and those excluded in this
 * one only. Apply re-scans the projects whose effective list changed: every open one for the global list,
 * this one for its own.
 */
class IndexExclusionsConfigurable(private val project: Project) : Configurable {

    private val globalArea = JBTextArea(6, 50)
    private val projectArea = JBTextArea(6, 50)

    override fun getDisplayName(): String = "Index Exclusions"

    override fun createComponent(): JComponent = panel {
        row {
            comment(
                "Paths matched here are excluded from indexing, as if marked Excluded, without changing the " +
                    "project model. One pattern per line, relative to the project directory: <code>*</code> and " +
                    "<code>?</code> stay within a path segment, <code>**</code> crosses segments, a pattern without " +
                    "<code>/</code> matches at any depth and a trailing <code>/</code> matches directories only.",
            )
        }
        group("All Projects") {
            row { scrollCell(globalArea).align(AlignX.FILL) }
        }
        group("This Project") {
            row { scrollCell(projectArea).align(AlignX.FILL) }
            row { comment("Applied on top of the list above, and kept in this project's workspace file.") }
        }
    }

    override fun isModified(): Boolean =
        entered(globalArea) != GlobalIndexExclusions.getInstance().patterns ||
            entered(projectArea) != ProjectIndexExclusions.getInstance(project).patterns

    override fun apply() {
        val global = GlobalIndexExclusions.getInstance()
        val own = ProjectIndexExclusions.getInstance(project)
        val globalChanged = entered(globalArea) != global.patterns
        val ownChanged = entered(projectArea) != own.patterns
        if (globalChanged) global.patterns = entered(globalArea)
        if (ownChanged) own.patterns = entered(projectArea)
        when {
            globalChanged -> ProjectManager.getInstance().openProjects.forEach(IndexExclusionContributor::rescan)
            ownChanged -> IndexExclusionContributor.rescan(project)
        }
        reset()
    }

    override fun reset() {
        globalArea.text = GlobalIndexExclusions.getInstance().patterns.joinToString("\n")
        projectArea.text = ProjectIndexExclusions.getInstance(project).patterns.joinToString("\n")
    }

    private fun entered(area: JBTextArea): List<String> = ExclusionPatterns.parseLines(area.text)
}
