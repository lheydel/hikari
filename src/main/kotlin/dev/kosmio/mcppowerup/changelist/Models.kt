package dev.kosmio.mcppowerup.changelist

import kotlinx.serialization.Serializable

/** One file inside a changelist. [changeType] ∈ MODIFIED / ADDED / DELETED / MOVED. */
@Serializable
data class FileEntry(val path: String, val changeType: String)

/**
 * A single local changelist with its files and metadata. Only the comment's first line is reported
 * (truncated to 120 chars), with [commentLength] giving the full comment's length; read the whole
 * comment with `get_changelist_comment`.
 */
@Serializable
data class ChangelistInfo(
    val name: String,
    val commentFirstLine: String,
    val commentLength: Int,
    val isDefault: Boolean,
    val isReadOnly: Boolean,
    val files: List<FileEntry>,
)

/**
 * Root result for `list_changelists` (wrapper object so the JSON schema has a named root).
 * [unversionedFiles] are project-relative paths of files not yet under version control (new files);
 * they belong to no changelist until scheduled for addition.
 */
@Serializable
data class ChangelistsResult(
    val changelists: List<ChangelistInfo>,
    val unversionedFiles: List<String>,
)

/** Result for `get_changelist_diff`. */
@Serializable
data class DiffResult(val name: String, val unifiedDiff: String)

/**
 * A file that could not be moved, with a machine-readable [reason]. [remainingIn] names the changelists
 * holding the file's changes, for the reasons that turn on where those changes live:
 * `split_across_changelists` (all its holders), `not_in_source` (the changelists that do hold it),
 * `move_incomplete` (what still holds it after the move).
 */
@Serializable
data class SkippedFile(
    val path: String,
    // not_modified | not_found | source_readonly | add_failed
    // | split_across_changelists | not_in_source | move_incomplete
    val reason: String,
    val remainingIn: List<String>? = null,
)

/**
 * Result for `move_to_changelist`. [movedCount] counts the requested files that landed — which ones
 * they are is the request minus [skippedFiles]. [addedFiles] are previously-unversioned files that
 * were scheduled for addition (add-to-VCS) into the target changelist.
 */
@Serializable
data class MoveResult(
    val movedCount: Int,
    val addedFiles: List<String>,
    val skippedFiles: List<SkippedFile>,
)

/**
 * Result for `create_changelist`. [movedCount] counts the requested files that landed — which ones
 * they are is the request minus [skippedFiles]. [addedFiles] are previously-unversioned files that
 * were scheduled for addition (add-to-VCS) into the new changelist.
 */
@Serializable
data class CreateResult(
    val name: String,
    val movedCount: Int,
    val addedFiles: List<String>,
    val skippedFiles: List<SkippedFile>,
)

/** Result for `delete_changelist`. */
@Serializable
data class DeleteResult(val deleted: Boolean)

/** Result for `refresh_changelists`. Call `list_changelists` afterward to read the refreshed state. */
@Serializable
data class RefreshResult(val refreshed: Boolean)

/** Result for `set_changelist_comment`: [length] is the character count of the comment now on the list. */
@Serializable
data class CommentResult(val applied: Boolean, val length: Int)

/** Result for `get_changelist_comment`: the changelist's full comment. */
@Serializable
data class GetCommentResult(val name: String, val comment: String)

/**
 * A 1-based, inclusive line range read from a diff hunk. In `move_lines_to_changelist`, `lineRanges`
 * uses current-file (`+` side) numbers and `deletedLineRanges` uses old-file (`-` side) numbers.
 */
@Serializable
data class LineRange(val start: Int, val end: Int)

/** One hunk that was moved by `move_lines_to_changelist`. */
@Serializable
data class MovedHunk(val lines: String, val fromChangelistId: String?)

/** Result for `move_lines_to_changelist`. */
@Serializable
data class MoveLinesResult(
    val file: String,
    val targetChangelist: String,
    val movedHunks: List<MovedHunk>,
    val unmatchedRanges: List<String>, // requested ranges that matched no hunk
)
