package dev.kosmio.hikari.analysis

/**
 * A declaration's coordinates, in exactly the shape `call_tree` accepts as a seed: a project-relative
 * [file] plus a [symbol] that names one declaration inside it. [symbol] is the declaration's name
 * qualified by its enclosing declarations (`ProjectCreateFeatureImpl.invoke`), with a `#N` suffix when
 * that qualified name occurs more than once in the file.
 */
internal data class NodeRef(val file: String, val symbol: String)

/**
 * Whether the IDE's copy of the file an answer was built from matches what is on disk. An edit made
 * outside the IDE is invisible until a re-scan, so [ideCopyMatchesDisk] = false means the IDE has not
 * re-scanned this file since it last changed on disk, and the answer may have been built from the older
 * text. It is deliberately the conservative direction: the IDE sometimes loads a file it had never
 * cached straight from disk and answers correctly anyway, and reporting that as fresh would need the
 * tool to know which of the two happened.
 */
internal data class Staleness(val file: String, val ideCopyMatchesDisk: Boolean)
