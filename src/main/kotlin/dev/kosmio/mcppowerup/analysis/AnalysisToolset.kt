package dev.kosmio.mcppowerup.analysis

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import kotlinx.coroutines.currentCoroutineContext

/**
 * Contributes code-intelligence tools to IntelliJ's built-in MCP server: what the IDE's indexes know
 * about a codebase, answered in one call rather than one call per hop.
 *
 * Read-only, like every tool this plugin contributes: nothing here edits file contents or VCS state.
 */
class AnalysisToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Reverse call tree: from one declaration, walk to what calls it, transitively, in a single call. " +
            "At every node it follows the references to the declaration and the declaration it " +
            "OVERRIDES, so an implementation reaches the callers of the interface or port it implements — " +
            "the hop a text search cannot make, since such an implementation often has no textual " +
            "reference to it anywhere. Project sources only: never libraries or the SDK. " +
            "IMPORTANT — zero callers does NOT mean dead code: framework entry points (@Scheduled, " +
            "@EventListener, @Bean endpoints, route files) have no callers by construction. And that " +
            "reading only holds when superHop.available is true; when it is false no override hop is " +
            "registered for the seed's language, so an empty result says nothing either way. " +
            "status tells you whether the traversal completed ('complete') or stopped ('stopped_at_depth', " +
            "'stopped_unresolved'); on either stopped status, frontier lists the nodes that were never " +
            "expanded as {file, symbol} pairs you can re-seed to continue — never read a stopped run as a " +
            "total answer. If you edited files with your own file tools, call refresh_changelists FIRST: " +
            "IntelliJ does not see external edits until it re-scans, and staleness in the result reports " +
            "whether the answer was built from the file as it stands on disk.",
    )
    suspend fun call_tree(
        @McpDescription("Project-relative path of the file holding the declaration to start from (use search_symbol to find it).")
        file: String,
        @McpDescription("The declaration to start from, named as `outline` reports it: its name qualified by its enclosing declarations (`ProjectCreateFeatureImpl.invoke`), with `#N` appended when the file holds more than one declaration under that qualified name. A bare name is accepted when it is unambiguous in the file; an ambiguous one comes back as an error listing the exact values to choose from.")
        symbol: String,
        @McpDescription("How many hops of callers to walk. 2 (the default) reaches the endpoint layer for a change in a domain declaration; beyond that a tree tends to drift into test helpers calling each other. Maximum 5. The first hop is always exhaustive.")
        depth: Int = CallTreeTraversal.DEFAULT_DEPTH,
        @McpDescription("'grouped' (the default) reports the callers per module and file with their declaration names, and carries the same caller set as 'tree' at about a third of the size — prefer it. 'tree' adds the tree structure, each node's project-relative path and its line number; ask for it when the locations are what you need.")
        format: String = CallTreeTraversal.FORMAT_GROUPED,
    ): CallTreeResult {
        val project = currentCoroutineContext().project
        if (depth < 1 || depth > CallTreeTraversal.MAX_DEPTH) {
            mcpFail("depth must be between 1 and ${CallTreeTraversal.MAX_DEPTH}, was $depth")
        }
        if (format != CallTreeTraversal.FORMAT_GROUPED && format != CallTreeTraversal.FORMAT_TREE) {
            mcpFail("format must be '${CallTreeTraversal.FORMAT_GROUPED}' or '${CallTreeTraversal.FORMAT_TREE}', was '$format'")
        }
        val basePath = readAction { project.basePath } ?: mcpFail("No project base path")
        return CallTreeTraversal(project, basePath).run(NodeRef(file, symbol), depth, format)
    }
}
