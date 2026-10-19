package dev.kosmio.hikari.analysis

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
class HikariAnalysisToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Reverse call tree: from one declaration, walk transitively to what calls it, in a single call. At each " +
        "node it follows references to the declaration AND what it OVERRIDES, so an implementation reaches its " +
        "port's callers — the hop a text search cannot make. The seed also reaches what OVERRIDES it, so a port's " +
        "contract covers code calling its implementations directly. Project sources only. OUTPUT (text): line 1 = seed, " +
        "caller count, depth, status (complete|stopped_at_depth|stopped_unresolved); line 2 = whether the override " +
        "hop resolved; then a `dirs:` table numbering directories D<n>; then the tree, where indent means 'calls " +
        "the line above'. A node ends with D<n>/<file>:<line>, or D<n>.<ext>:<line> meaning the file is the " +
        "declaration's outermost segment plus that extension (`Foo.bar  D2.kt:9` = D2/Foo.kt line 9). Marks: * " +
        "never expanded (a stopped run — re-run deeper; never read it as a total answer), ^ reached via the " +
        "override hop, ↓ an implementation of the seed, (test) test source. Zero callers is NOT dead code: framework entry points (@Scheduled, " +
        "@EventListener, @Bean, route files) have none by construction — and that reading only holds if line 2 says " +
        "the hop resolved. Edited files with your own tools? Call refresh_changelists FIRST; a STALE line means the " +
        "IDE has not re-scanned."
    )
    suspend fun call_tree(
        @McpDescription("Project-relative path of the file holding the seed declaration (search_symbol finds it).")
        file: String,
        @McpDescription("The declaration, as `outline` prints it: its name qualified by its enclosing declarations (`ProjectCreateFeatureImpl.invoke`), plus `#N` when that qualified name repeats in the file. A bare name works when unambiguous; an ambiguous one errors, listing the exact values to choose from.")
        symbol: String,
        @McpDescription("Hops of callers to walk. 2 (the default) reaches the endpoint layer for a change in a domain declaration; beyond that a tree drifts into test helpers calling each other. Max 5. The first hop is always exhaustive.")
        depth: Int = CallTreeTraversal.DEFAULT_DEPTH,
    ): String {
        val project = currentCoroutineContext().project
        if (depth < 1 || depth > CallTreeTraversal.MAX_DEPTH) {
            mcpFail("depth must be between 1 and ${CallTreeTraversal.MAX_DEPTH}, was $depth")
        }
        val basePath = readAction { project.basePath } ?: mcpFail("No project base path")
        return CallTreeTraversal(project, basePath).run(NodeRef(file, symbol), depth)
    }

    @McpTool
    @McpDescription(
        "A file's shape without its contents: every declaration with the IDE's signature for it and the lines it " +
        "spans — never body text. Read this INSTEAD of a whole file, then read only the range you need. OUTPUT " +
        "(text): line 1 = file, language, length; then one line per declaration as `START-END signature`, indented " +
        "under whatever it nests in. START/END are 1-based inclusive and cover the doc comment and annotations too. " +
        "To seed call_tree use the signature's leading identifier — unless the line ends with [symbol], which means " +
        "the bare name would not resolve and you must pass the bracketed value verbatim. A trailing `... N more not " +
        "listed` line means the output cap bound; read the file for the rest. Edited the file with your own tools? " +
        "Call refresh_changelists FIRST; a STALE line means the IDE has not re-scanned."
    )
    suspend fun outline(
        @McpDescription("Project-relative path of the file to outline.")
        file: String,
    ): String {
        val project = currentCoroutineContext().project
        val basePath = readAction { project.basePath } ?: mcpFail("No project base path")
        return OutlineBuilder(project, basePath).run(file)
    }
}
