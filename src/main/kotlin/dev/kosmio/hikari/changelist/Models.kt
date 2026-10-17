package dev.kosmio.hikari.changelist

import kotlinx.serialization.Serializable

/**
 * A file that could not be moved, with a machine-readable [reason]. [remainingIn] names the changelists
 * holding the file's changes, for the reasons that turn on where those changes live:
 * `split_across_changelists` (all its holders), `not_in_source` (the changelists that do hold it),
 * `move_incomplete` (what still holds it after the move).
 */
internal data class SkippedFile(
    val path: String,
    // not_modified | not_found | source_readonly | add_failed
    // | split_across_changelists | not_in_source | move_incomplete
    val reason: String,
    val remainingIn: List<String>? = null,
)

/**
 * A 1-based, inclusive line range read from a diff hunk. In `move_lines_to_changelist`, `lineRanges`
 * uses current-file (`+` side) numbers and `deletedLineRanges` uses old-file (`-` side) numbers.
 *
 * This one stays `@Serializable`: it is a tool *parameter*, so the framework generates its input schema.
 */
@Serializable
data class LineRange(val start: Int, val end: Int)
