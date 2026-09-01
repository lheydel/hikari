package dev.kosmio.mcppowerup.analysis

import kotlinx.serialization.Serializable

/**
 * A declaration's coordinates, in exactly the shape `call_tree` accepts as a seed: a project-relative
 * [file] plus a [symbol] that names one declaration inside it. [symbol] is the declaration's name
 * qualified by its enclosing declarations (`ProjectCreateFeatureImpl.invoke`), with a `#N` suffix when
 * that qualified name occurs more than once in the file.
 */
@Serializable
data class NodeRef(val file: String, val symbol: String)

/**
 * Whether the override hop resolved for the seed's [language]. When [available] is false no provider
 * handles that language, so a declaration reachable only through what it overrides looks like it has no
 * callers — an empty result is uninformative rather than a framework root.
 */
@Serializable
data class SuperHop(val language: String, val available: Boolean, val providers: List<String>)

/**
 * Whether the IDE's copy of the seed file matches what is on disk. An edit made outside the IDE is
 * invisible until a re-scan, so [ideCopyMatchesDisk] = false means the answer was built from the file as
 * it stood before that edit; [hint] says what to do about it.
 */
@Serializable
data class Staleness(val file: String, val ideCopyMatchesDisk: Boolean, val hint: String? = null)

/**
 * One node of the `tree` format. The tree comes back FLAT, breadth-first, with the seed at index 0:
 * [calls] is the index in the same list of the node this one calls, and -1 for the seed. Reading it as
 * a tree means grouping by [calls]; a node's index is always greater than its [calls], so one pass in
 * order is enough.
 *
 * Flat rather than nested because a self-referential `@Serializable` type breaks the MCP framework's
 * return-type schema generation, and the tool is then dropped from the served list without an error the
 * caller ever sees.
 */
@Serializable
data class CallNode(
    val symbol: String,
    val file: String,
    val line: Int,
    val module: String,
    val isTest: Boolean,
    val calls: Int,
    /** True when this node is what the node it [calls] overrides, rather than one of its callers. */
    val overrides: Boolean = false,
    /** True when the traversal stopped before expanding this node; it is also in the result's frontier. */
    val unexpanded: Boolean = false,
)

/** Callers in one file of the `grouped` format. [file] is the file's name, not its path — ask for `tree` when the locations are the point. */
@Serializable
data class CallerFile(val file: String, val symbols: List<String>)

/** Callers in one module of the `grouped` format. */
@Serializable
data class CallerModule(val module: String, val callerCount: Int, val files: List<CallerFile>)

/**
 * The `grouped` format: the same caller set the `tree` format carries, with per-node paths, line numbers
 * and tree structure dropped. [test] holds the callers in test sources, kept apart from [production] so a
 * test caller never reads as production impact.
 */
@Serializable
data class GroupedCallers(val production: List<CallerModule>, val test: List<CallerModule>)

/**
 * Result for `call_tree`. [status] is `complete`, `stopped_at_depth` or `stopped_unresolved`; on either
 * stopped status [frontier] carries the nodes that were never expanded, re-seedable as they stand.
 * Exactly one of [grouped] and [tree] is populated, per the caller's `format`.
 */
@Serializable
data class CallTreeResult(
    val seed: NodeRef,
    val depth: Int,
    val status: String,
    val callerCount: Int,
    val superHop: SuperHop,
    val staleness: Staleness,
    val grouped: GroupedCallers? = null,
    val tree: List<CallNode>? = null,
    val frontier: List<NodeRef> = emptyList(),
)
