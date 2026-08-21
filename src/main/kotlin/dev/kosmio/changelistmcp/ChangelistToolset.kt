package dev.kosmio.changelistmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangeListManagerEx
import com.intellij.openapi.vcs.changes.ChangeListManagerImpl
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.changes.LocalChangeList
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vcs.changes.actions.ScheduleForAdditionAction
import com.intellij.openapi.vcs.ex.PartialLocalLineStatusTracker
import com.intellij.openapi.vcs.ex.Range
import com.intellij.openapi.vcs.impl.LineStatusTrackerManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Consumer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.StringWriter
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

/**
 * Contributes changelist-management tools to IntelliJ's built-in MCP server.
 *
 * Read + organize only: this toolset never commits, pushes, stashes, reverts, shelves, or edits file
 * contents. To organize new files it may schedule them for addition (add-to-VCS) into a changelist —
 * the git equivalent of marking a new file to be tracked — but the user still reviews and commits
 * manually. That boundary is absolute.
 */
class ChangelistToolset : McpToolset {

    @McpTool
    @McpDescription("List IntelliJ local changelists with their files and metadata, plus the project's unversioned (new, not-yet-tracked) files. File paths are project-relative; changeType is one of MODIFIED/ADDED/DELETED/MOVED. New files appear under unversionedFiles until grouped into a changelist with create_changelist/move_to_changelist (which schedule them for addition).")
    suspend fun list_changelists(): ChangelistsResult {
        val project = currentCoroutineContext().project
        return readAction {
            val basePath = project.basePath ?: mcpFail("No project base path")
            val clm = ChangeListManager.getInstance(project)
            val lists = clm.changeLists.map { list ->
                ChangelistInfo(
                    name = list.name,
                    comment = list.comment ?: "",
                    isDefault = list.isDefault,
                    isReadOnly = list.isReadOnly,
                    files = list.changes.map { change ->
                        FileEntry(relativize(basePath, change), mapChangeType(change))
                    },
                )
            }
            val unversioned = clm.unversionedFilesPaths.map { relativizePath(basePath, it.path) }
            ChangelistsResult(changelists = lists, unversionedFiles = unversioned)
        }
    }

    @McpTool
    @McpDescription("Re-scan the working tree so IntelliJ sees files changed outside the IDE, and recompute changelist state. Call this BEFORE list_changelists / the move tools whenever you have created, edited, or deleted files with Claude's own file tools — IntelliJ does not detect external edits until it re-scans, so those tools would otherwise miss your changes. Blocks until the re-scan completes; then call list_changelists to read the refreshed state.")
    suspend fun refresh_changelists(): RefreshResult {
        val project = currentCoroutineContext().project
        val basePath = readAction { project.basePath } ?: mcpFail("No project base path")
        val baseDir = readAction { LocalFileSystem.getInstance().findFileByPath(basePath) }
            ?: mcpFail("Project base directory not found in the VFS")

        // Synchronous VFS refresh + VCS dirty recompute must run off-EDT and off any read/write lock.
        // The VFS refresh spawns asynchronous VCS dirty marking, so a single waitForUpdate can return
        // while a trailing update is still scheduled — drain those so control returns only when the
        // pipeline is quiescent (otherwise a trailing update clobbers the next mutation's worker seed).
        withContext(Dispatchers.IO) {
            VfsUtil.markDirtyAndRefresh(false, true, true, baseDir)
            VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
            val clm = ChangeListManagerImpl.getInstanceImpl(project)
            clm.waitForUpdate()
            var guard = 0
            while (clm.isInUpdate() && guard++ < MAX_SETTLE_DRAINS) clm.waitForUpdate()
        }
        return RefreshResult(refreshed = true)
    }

    @McpTool
    @McpDescription("Return the unified diff for a single changelist, used to draft a commit message. Output is capped at 200 KB.")
    suspend fun get_changelist_diff(
        @McpDescription("Name of the changelist to diff.") name: String,
    ): DiffResult {
        val project = currentCoroutineContext().project
        return readAction {
            val basePath = project.basePath ?: mcpFail("No project base path")
            val list = ChangeListManager.getInstance(project).changeLists.firstOrNull { it.name == name }
                ?: mcpFail("Changelist '$name' not found")
            val diff = buildUnifiedDiff(project, basePath, list.changes.toList())
            DiffResult(name = name, unifiedDiff = diff)
        }
    }

    @McpTool
    @McpDescription("Create a new changelist and optionally move the listed files into it. Currently-modified files are moved whole; unversioned (new) files are scheduled for addition (add-to-VCS) into the new changelist and reported in addedFiles (not when `from` is set — see below). A file whose hunks are SPLIT across several changelists is refused with reason split_across_changelists (remainingIn names its holders), because moving it whole would drag along hunks you did not claim — pass `from` to take only the hunks one changelist holds, or use move_lines_to_changelist for hunk-level precision. movedFiles reports only what actually landed. Files that are neither modified nor unversioned, or not found, are reported in skippedFiles; the call still succeeds.")
    suspend fun create_changelist(
        @McpDescription("Name for the new changelist.") name: String,
        @McpDescription("Optional description/comment for the changelist.") comment: String? = null,
        @McpDescription("Optional project-relative file paths to put into the new changelist (modified files are moved, new files are added).") files: List<String>? = null,
        @McpDescription("If true, make the new changelist the active (default) one.") setActive: Boolean = false,
        @McpDescription("Optional name of the changelist to take the changes FROM. It is a strict filter over every path in `files`: ONLY what that changelist holds moves. A file it does not hold is skipped with reason not_in_source — including a file that sits entirely in some other changelist, and including a new/unversioned file, which no changelist holds (so a scoped call never schedules one for addition). On a file split across changelists, only its hunks in `from` move and the rest stay put. Must name an existing changelist, and must differ from the target. Omit to move whole files, which then refuses split files.") from: String? = null,
    ): CreateResult {
        val project = currentCoroutineContext().project
        val basePath = readAction { project.basePath } ?: mcpFail("No project base path")
        val clm = ChangeListManagerImpl.getInstanceImpl(project)
        // Create + (optional) set-default on the updater thread so they are serialized with the update
        // pipeline. putFilesInto then runs on the same serialized queue, so the new list already exists
        // when the move runs.
        onUpdaterThread(clm) {
            val newList = clm.addChangeList(name, comment ?: "")
            if (setActive) clm.setDefaultChangeList(newList)
        }
        val outcome = putFilesInto(project, clm, basePath, name, files ?: emptyList(), from)
        return CreateResult(name = name, movedFiles = outcome.moved, addedFiles = outcome.added, skippedFiles = outcome.skipped)
    }

    @McpTool
    @McpDescription("Put files into an EXISTING changelist. Currently-modified files are moved whole; unversioned (new) files are scheduled for addition (add-to-VCS) into it and reported in addedFiles (not when `from` is set — see below). A file whose hunks are SPLIT across several changelists is refused with reason split_across_changelists (remainingIn names its holders), because moving it whole would drag along hunks you did not claim — typically a parallel session's. For a split file either pass `from` to take only the hunks one changelist holds, or use move_lines_to_changelist for hunk-level precision. movedFiles reports only what actually landed. Fails if the target changelist does not exist (it is never auto-created) or is read-only. Files that are neither modified nor unversioned, or not found, are reported in skippedFiles.")
    suspend fun move_to_changelist(
        @McpDescription("Name of the existing target changelist.") name: String,
        @McpDescription("Project-relative file paths to put into the target changelist (modified files are moved, new files are added).") files: List<String>,
        @McpDescription("Optional name of the changelist to take the changes FROM. It is a strict filter over every path in `files`: ONLY what that changelist holds moves. A file it does not hold is skipped with reason not_in_source — including a file that sits entirely in some other changelist, and including a new/unversioned file, which no changelist holds (so a scoped call never schedules one for addition). On a file split across changelists, only its hunks in `from` move and the rest stay put. Must name an existing changelist, and must differ from the target. Omit to move whole files, which then refuses split files.") from: String? = null,
    ): MoveResult {
        val project = currentCoroutineContext().project
        val basePath = readAction { project.basePath } ?: mcpFail("No project base path")
        val clm = ChangeListManagerImpl.getInstanceImpl(project)
        readAction {
            val target = clm.changeLists.firstOrNull { it.name == name }
                ?: mcpFail("Changelist '$name' not found — refusing to auto-create it")
            if (target.isReadOnly) mcpFail("Changelist '$name' is read-only — refusing to write into a frozen changelist")
        }
        val outcome = putFilesInto(project, clm, basePath, name, files, from)
        return MoveResult(movedFiles = outcome.moved, addedFiles = outcome.added, skippedFiles = outcome.skipped)
    }

    @McpTool
    @McpDescription("Delete a changelist, relocating its files first. By default the files move to the active default changelist; pass moveContentsTo to send them to a specific existing changelist instead. Refuses to delete the default changelist. Fails if the named relocation target does not exist.")
    suspend fun delete_changelist(
        @McpDescription("Name of the changelist to delete.") name: String,
        @McpDescription("Name of an existing changelist to move this list's files into before deletion. Defaults to the active default changelist (whatever it is named).") moveContentsTo: String? = null,
    ): DeleteResult {
        val project = currentCoroutineContext().project
        val clm = ChangeListManagerImpl.getInstanceImpl(project)
        // Validate up front (read-only); fail fast before touching the update pipeline.
        readAction {
            val list = clm.changeLists.firstOrNull { it.name == name }
                ?: mcpFail("Changelist '$name' not found")
            if (list.isDefault) mcpFail("Refusing to delete the Default changelist")
            if (moveContentsTo != null && clm.changeLists.none { it.name == moveContentsTo }) {
                mcpFail("Relocation target changelist '$moveContentsTo' not found")
            }
        }
        // Relocate contents then remove, serialized with the update pipeline. Re-fetch the list/target
        // and its changes inside the runnable — never act on Change objects cached across the suspension.
        onUpdaterThread(clm) {
            ApplicationManager.getApplication().runReadAction(Runnable {
                val list = clm.changeLists.firstOrNull { it.name == name } ?: return@Runnable
                val changes = list.changes.toList()
                if (changes.isNotEmpty()) {
                    val target = if (moveContentsTo != null) {
                        clm.changeLists.firstOrNull { it.name == moveContentsTo } ?: return@Runnable
                    } else {
                        clm.defaultChangeList
                    }
                    clm.moveChangesTo(target, *changes.toTypedArray())
                }
                clm.removeChangeList(list.name)
            })
        }
        settle(clm)
        return DeleteResult(deleted = true)
    }

    @McpTool
    @McpDescription(
        "Set a changelist's comment, which IntelliJ uses as the default commit message for that " +
            "changelist. Draft the message from get_changelist_diff, then set it here so the IDE's " +
            "commit dialog is pre-filled — the user reviews and commits without any copy/paste. " +
            "IMPORTANT: this only works on a NAMED changelist; the default changelist's comment is " +
            "ignored by the commit dialog (it shows the last commit message instead), so group the " +
            "changes into a named changelist first. Setting a comment never commits or pushes.",
    )
    suspend fun set_changelist_comment(
        @McpDescription("Name of the changelist whose comment (default commit message) to set.") name: String,
        @McpDescription("The comment / default commit message to set on the changelist.") comment: String,
    ): CommentResult {
        val project = currentCoroutineContext().project
        val clm = ChangeListManagerImpl.getInstanceImpl(project)
        readAction {
            clm.changeLists.firstOrNull { it.name == name } ?: mcpFail("Changelist '$name' not found")
        }
        // Apply on the updater thread, settle, and verify the comment stuck — a trailing update can
        // otherwise rebuild the worker from a pre-edit seed and revert it. Reapply up to MAX_REAPPLY.
        var attempt = 0
        while (true) {
            onUpdaterThread(clm) { clm.editComment(name, comment) }
            settle(clm)
            val current = readAction { clm.changeLists.firstOrNull { it.name == name }?.comment ?: "" }
            if (current == comment || ++attempt >= MAX_REAPPLY) break
        }
        return CommentResult(name = name, comment = comment)
    }

    @McpTool
    @McpDescription(
        "Move only specific line ranges (hunks) of a single file into an EXISTING changelist, leaving " +
            "the file's other changes where they are. Use this when a file contains changes from more " +
            "than one source (e.g. parallel sessions): read get_changelist_diff to find your hunks, then " +
            "move just those. For ADDED or MODIFIED hunks, pass lineRanges = the 1-based CURRENT-file line " +
            "numbers (the '+' side of each '@@ -a,b +c,d @@' header). For PURE DELETION hunks (only '-' " +
            "lines, no '+' line to point at), pass deletedLineRanges = the 1-based OLD-file line numbers of " +
            "the removed lines (count up from 'a' in the '@@ -a,b +c,d @@' header). Each hunk that overlaps " +
            "a requested range on its matching side is moved. Fails if the target changelist is missing " +
            "(never auto-created) or read-only. If the file cannot be partially tracked, use " +
            "move_to_changelist to move the whole file instead.",
    )
    suspend fun move_lines_to_changelist(
        @McpDescription("Name of the existing target changelist.") name: String,
        @McpDescription("Project-relative path of the file whose hunks to move.") file: String,
        @McpDescription("1-based, inclusive CURRENT-file line ranges to move (the diff's '+' side) — for added/modified hunks. May be empty if you are only moving deletions via deletedLineRanges.") lineRanges: List<LineRange>,
        @McpDescription("1-based, inclusive OLD-file line ranges to move (the diff's '-' side) — use this to target PURE DELETION hunks, which have no '+' line. Optional; omit when there are no deletions to move.") deletedLineRanges: List<LineRange>? = null,
    ): MoveLinesResult {
        val project = currentCoroutineContext().project
        val basePath = readAction { project.basePath } ?: mcpFail("No project base path")
        val deleted = deletedLineRanges ?: emptyList()
        if (lineRanges.isEmpty() && deleted.isEmpty()) {
            mcpFail("Provide at least one range in lineRanges (current-file '+' lines) or deletedLineRanges (old-file '-' lines)")
        }
        val vFile = LocalFileSystem.getInstance().findFileByPath("$basePath/$file")
            ?: mcpFail("File '$file' not found")
        if (!ensurePartialTracker(project, vFile)) {
            mcpFail(
                "No partial line-status tracker available for '$file' — it may have no changes or not be " +
                    "under partial-changelist tracking. Use move_to_changelist to move the whole file instead.",
            )
        }

        return edtWriteAction {
            val clm = ChangeListManagerEx.getInstanceEx(project)
            val target = clm.changeLists.firstOrNull { it.name == name }
                ?: mcpFail("Changelist '$name' not found — refusing to auto-create it")
            if (target.isReadOnly) mcpFail("Changelist '$name' is read-only — refusing to write into a frozen changelist")

            // Re-fetch the tracker inside the write action — never act on one cached across the suspension.
            val tracker = LineStatusTrackerManager.getInstance(project).getLineStatusTracker(vFile)
                as? PartialLocalLineStatusTracker
                ?: mcpFail("Line-status tracker for '$file' is no longer available")

            val ranges = tracker.getRanges().orEmpty()
            val movedHunks = mutableListOf<MovedHunk>()
            val matched = BooleanArray(lineRanges.size)
            val matchedDeleted = BooleanArray(deleted.size)
            val toMove = mutableListOf<Range>()
            ranges.forEach { r ->
                var hit = false
                // Added/modified hunks are targeted by their current-file ('+' side) lines.
                lineRanges.forEachIndexed { j, req ->
                    if (rangeOverlaps(r, req.start, req.end)) {
                        hit = true
                        matched[j] = true
                    }
                }
                // Pure deletions (and the deleted side of modifications) are targeted by old-file ('-') lines.
                deleted.forEachIndexed { j, req ->
                    if (vcsRangeOverlaps(r, req.start, req.end)) {
                        hit = true
                        matchedDeleted[j] = true
                    }
                }
                if (hit) {
                    toMove += r
                    movedHunks += MovedHunk(formatSpan(r), r.changelistId)
                }
            }
            // Move each matched hunk by its Range. Reassigning a hunk's changelist doesn't change line
            // content, so the remaining Range objects stay valid across the loop.
            for (range in toMove) tracker.moveToChangelist(range, target)
            // Report requested ranges that matched no hunk; deleted-side ranges are prefixed "-" to distinguish.
            val unmatched = lineRanges.filterIndexed { j, _ -> !matched[j] }.map { "${it.start}-${it.end}" } +
                deleted.filterIndexed { j, _ -> !matchedDeleted[j] }.map { "-${it.start}..${it.end}" }
            MoveLinesResult(file = file, targetChangelist = name, movedHunks = movedHunks, unmatchedRanges = unmatched)
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    /**
     * Ensure a [PartialLocalLineStatusTracker] exists for [vFile] with its hunks computed. Trackers are
     * created lazily, so if none exists we request one (off any open editor — a loaded Document is enough)
     * and poll until its ranges are available or [MAX_TRACKER_POLLS] elapses. We intentionally do not
     * release the tracker afterwards: releasing it risks discarding the pending move before the changelist
     * manager persists it, and retaining one tracker per touched file for the session is negligible.
     */
    private suspend fun ensurePartialTracker(project: Project, vFile: VirtualFile): Boolean {
        val ltm = LineStatusTrackerManager.getInstance(project)
        if (readAction { ltm.getLineStatusTracker(vFile) } is PartialLocalLineStatusTracker) return true

        val document = readAction { FileDocumentManager.getInstance().getDocument(vFile) } ?: return false
        edtWriteAction { ltm.requestTrackerFor(document, trackerRequester) }

        repeat(MAX_TRACKER_POLLS) {
            delay(TRACKER_POLL_MS)
            val tracker = readAction { ltm.getLineStatusTracker(vFile) }
            if (tracker is PartialLocalLineStatusTracker && readAction { tracker.getRanges().orEmpty().isNotEmpty() }) {
                return true
            }
        }
        return readAction { ltm.getLineStatusTracker(vFile) } is PartialLocalLineStatusTracker
    }

    /** Whether hunk [range]'s current-file ('+' side) span overlaps the 1-based inclusive [reqStart]..[reqEnd]. */
    private fun rangeOverlaps(range: Range, reqStart: Int, reqEnd: Int): Boolean {
        val hasLines = range.line2 > range.line1
        // Tracker lines are 0-based half-open [line1, line2); convert to 1-based inclusive. A pure deletion
        // (line1 == line2) sits at the boundary, so match it against the line just before/after.
        val start = if (hasLines) range.line1 + 1 else maxOf(1, range.line1)
        val end = if (hasLines) range.line2 else range.line1 + 1
        return reqStart <= end && start <= reqEnd
    }

    /**
     * Whether hunk [range]'s old-file ('-' side) span overlaps the 1-based inclusive [reqStart]..[reqEnd].
     * This is how PURE DELETION hunks (which occupy no current-file lines) — and the deleted side of a
     * modification — are targeted. vcsLine1..vcsLine2 are 0-based half-open old-file lines.
     */
    private fun vcsRangeOverlaps(range: Range, reqStart: Int, reqEnd: Int): Boolean {
        if (!range.hasVcsLines()) return false
        val start = range.vcsLine1 + 1
        val end = range.vcsLine2
        return reqStart <= end && start <= reqEnd
    }

    /** Human-readable 1-based span of a hunk: "10-14" (current file) or "-8..9 (deleted)" (old file). */
    private fun formatSpan(range: Range): String = when {
        range.line2 > range.line1 -> "${range.line1 + 1}-${range.line2}"
        range.hasVcsLines() -> "-${range.vcsLine1 + 1}..${range.vcsLine2} (deleted)"
        else -> "${range.line1}(deletion)"
    }


    /** Outcome of partitioning a set of requested paths in [moveFilesInto]. */
    private class MoveOutcome(
        val moved: List<String>,
        val added: List<String>,
        val skipped: List<SkippedFile>,
    )

    /**
     * Put each project-relative path into the changelist named [targetName]: any tracked change
     * (modified / added / deleted / moved) is moved by its [Change]; unversioned (new) files are scheduled
     * for addition (add-to-VCS). Partitions into moved / added / skipped (not_found / not_modified /
     * source_readonly / add_failed / split_across_changelists / not_in_source / move_incomplete).
     * Resolution is by the change set keyed on path, so deleted and moved files — which have no live
     * VirtualFile — resolve correctly.
     *
     * [sourceName] scopes the whole call to one changelist's changes, uniformly: every path is kept only
     * if that changelist holds it, so a file living entirely in another changelist and a new file (which
     * no changelist holds) are both skipped as not_in_source. It is what makes a file split across
     * changelists tractable — only the hunks that changelist holds move, the others stay where they are.
     * Without it a split file is refused rather than consolidated, since moving it whole would relocate
     * hunks the caller never claimed.
     *
     * Three phases: (1a) classify the requested paths under a read action; (1b) move tracked changes on
     * the ChangeListManager updater thread (via [moveByPaths]) so the move is serialized with the update
     * pipeline and a trailing/racing update can't revert it; (2) schedule unversioned files for addition
     * with a synchronous VCS task that must NOT hold the write lock and must be off the EDT — it runs on
     * [Dispatchers.IO] via the non-modal `addUnversionedFilesToVcsInSync`; (3) settle and verify the moved
     * files landed, reapplying any the pipeline reverted ([verifyMovedOrReapply]). Change objects are
     * re-resolved by path inside each updater-thread runnable; nothing but stable paths crosses suspensions.
     */
    private suspend fun putFilesInto(
        project: Project,
        clm: ChangeListManagerImpl,
        basePath: String,
        targetName: String,
        files: List<String>,
        sourceName: String?,
    ): MoveOutcome {
        if (sourceName == targetName) {
            mcpFail("Source and target changelist are both '$targetName' — nothing to move")
        }
        val lfs = LocalFileSystem.getInstance()
        val moved = mutableListOf<String>()
        val added = mutableListOf<String>()
        val skipped = mutableListOf<SkippedFile>()
        val toAdd = mutableListOf<VirtualFile>()

        // Phase 1a: partition under a read action (classification only — no mutation yet). Resolving via
        // the change set (rather than a VFS lookup) is what lets DELETED and MOVED files resolve — their
        // original path has no live VirtualFile, so findFileByPath would wrongly report them not_found.
        readAction {
            if (clm.changeLists.none { it.name == targetName }) mcpFail("Changelist '$targetName' not found")
            if (sourceName != null && clm.changeLists.none { it.name == sourceName }) {
                mcpFail("Source changelist '$sourceName' not found")
            }
            val changesByPath = currentChangesByPath(clm, basePath)
            for (rel in files) {
                val held = changesByPath[rel]
                if (!held.isNullOrEmpty()) {
                    val holders = held.map { it.listName }.distinct()
                    // Which of the path's changes this call is allowed to touch: the ones held by
                    // sourceName, or all of them when no source was named.
                    val relevant = if (sourceName == null) held else held.filter { it.listName == sourceName }
                    when {
                        // A file split across changelists is ambiguous without a source: moving it whole
                        // would drag along hunks this caller never claimed (typically a parallel session's).
                        sourceName == null && holders.size > 1 ->
                            skipped += SkippedFile(rel, "split_across_changelists", holders)
                        relevant.isEmpty() -> skipped += SkippedFile(rel, "not_in_source", holders)
                        relevant.any { it.listIsReadOnly } -> skipped += SkippedFile(rel, "source_readonly")
                        else -> moved += rel
                    }
                    continue
                }
                // Not a tracked change → either an unversioned new file (schedule for addition) or unknown.
                // No changelist holds an unversioned file, so a call scoped to one cannot claim it either:
                // sourceName filters uniformly, tracked change or not.
                val vFile = lfs.findFileByPath("$basePath/$rel")
                when {
                    vFile == null -> skipped += SkippedFile(rel, "not_found")
                    !clm.isUnversioned(vFile) -> skipped += SkippedFile(rel, "not_modified")
                    sourceName != null -> skipped += SkippedFile(rel, "not_in_source", emptyList())
                    else -> {
                        toAdd += vFile
                        added += rel
                    }
                }
            }
        }

        // Phase 1b: move the tracked changes on the updater thread, serialized with the update pipeline so
        // a trailing/racing update (from an external-edit VFS refresh) cannot rebuild the worker from a
        // seed snapshot that predates the move.
        if (moved.isNotEmpty()) moveByPaths(clm, basePath, targetName, moved, sourceName)

        // Phase 2: schedule unversioned files for addition — synchronous, off-EDT, no write lock, no modal.
        if (toAdd.isNotEmpty()) {
            val ok = withContext(Dispatchers.IO) {
                val target = readAction { clm.changeLists.firstOrNull { it.name == targetName } }
                if (target == null) {
                    false
                } else {
                    ScheduleForAdditionAction.Manager.addUnversionedFilesToVcsInSync(
                        project, target, toAdd, Consumer { },
                    )
                }
            }
            if (!ok) {
                skipped += added.map { SkippedFile(it, "add_failed") }
                added.clear()
            }
        }

        // Phase 3: settle the pipeline and verify the moved files actually landed in the target; reapply
        // any that a trailing update reverted. Bounded — whatever the source still holds is demoted from
        // moved to skipped, so the result reports what really moved.
        if (moved.isNotEmpty()) {
            val stuck = verifyMovedOrReapply(clm, basePath, targetName, moved, sourceName)
            if (stuck.isNotEmpty()) {
                moved.removeAll(stuck.keys)
                skipped += stuck.map { (rel, elsewhere) -> SkippedFile(rel, "move_incomplete", elsewhere) }
            }
        }

        return MoveOutcome(moved, added, skipped)
    }

    /** One of a path's changes, together with the changelist holding it. */
    private data class HeldChange(val listName: String, val listIsReadOnly: Boolean, val change: Change)

    /**
     * Index every current change by project-relative path, keeping ALL of a path's changes rather than
     * one per path. A file whose hunks are split across changelists (partial changelists) appears once
     * per holding list, and each of those entries is a distinct `ChangeListChange` carrying its own list
     * id — the platform relocates only the hunks of the list a given entry names, so which entries are
     * passed decides what moves. Requires read access (call inside a read action).
     */
    private fun currentChangesByPath(clm: ChangeListManagerImpl, basePath: String): Map<String, List<HeldChange>> {
        val map = HashMap<String, MutableList<HeldChange>>()
        for (list in clm.changeLists) {
            for (change in list.changes) {
                map.getOrPut(relativize(basePath, change)) { mutableListOf() } +=
                    HeldChange(list.name, list.isReadOnly, change)
            }
        }
        return map
    }

    /**
     * Move the changes for [paths] into [targetName] on the ChangeListManager updater thread. Change
     * objects are re-resolved by path INSIDE the runnable (under a read action) so we never act on
     * instances cached across the suspension, and the move cannot interleave with an update's
     * snapshot/rebuild. When [sourceName] is set only the changes that changelist holds are passed, which
     * is what makes the platform relocate just its hunks of a split file. A missing target list inside the
     * runnable is a no-op (validated by the caller).
     */
    private suspend fun moveByPaths(
        clm: ChangeListManagerImpl,
        basePath: String,
        targetName: String,
        paths: List<String>,
        sourceName: String?,
    ) {
        onUpdaterThread(clm) {
            ApplicationManager.getApplication().runReadAction(Runnable {
                val target = clm.changeLists.firstOrNull { it.name == targetName } ?: return@Runnable
                val byPath = currentChangesByPath(clm, basePath)
                val toMove = paths
                    .flatMap { byPath[it].orEmpty() }
                    .filter { sourceName == null || it.listName == sourceName }
                    .map { it.change }
                if (toMove.isNotEmpty()) clm.moveChangesTo(target, *toMove.toTypedArray())
            })
        }
    }

    /**
     * Settle the pipeline and confirm the changes this call claimed have left their source; reapply any
     * that regressed. Bounded by [MAX_REAPPLY]. With [sourceName] set, a path is done once that changelist
     * no longer holds it — the changelists this call never claimed keep their hunks. Without a source, only unsplit files reach this point, so being held
     * anywhere but the target is a regression. A path whose change has since disappeared is not one.
     * Returns the paths that did not land, mapped to the changelists still holding them.
     */
    private suspend fun verifyMovedOrReapply(
        clm: ChangeListManagerImpl,
        basePath: String,
        targetName: String,
        moved: List<String>,
        sourceName: String?,
    ): Map<String, List<String>> {
        var pending = moved.associateWith { emptyList<String>() }
        var attempt = 0
        while (true) {
            settle(clm)
            pending = readAction {
                val byPath = currentChangesByPath(clm, basePath)
                pending.keys.mapNotNull { rel ->
                    val stillHolding = byPath[rel].orEmpty()
                        .map { it.listName }
                        .filter { if (sourceName != null) it == sourceName else it != targetName }
                    if (stillHolding.isEmpty()) null else rel to stillHolding.distinct()
                }.toMap()
            }
            if (pending.isEmpty() || ++attempt >= MAX_REAPPLY) break
            moveByPaths(clm, basePath, targetName, pending.keys.toList(), sourceName)
        }
        return pending
    }

    /**
     * Run [mutate] on the ChangeListManager updater thread (the serialized ChangeListScheduler queue that
     * also runs VCS updates) and block until it completes. Serializing the mutation with the update
     * pipeline guarantees it cannot interleave with an update's worker snapshot/rebuild, so a
     * trailing/racing update seeds from the mutated worker and preserves the change. [mutate] runs OFF the
     * EDT and holds no read lock — wrap any reads it does in a runReadAction { }. The ChangeListManagerImpl
     * mutators it calls (moveChangesTo / editComment / addChangeList / setDefaultChangeList /
     * removeChangeList) acquire read access internally.
     */
    private suspend fun onUpdaterThread(clm: ChangeListManagerImpl, mutate: () -> Unit) {
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        clm.executeOnUpdaterThread {
            try {
                mutate()
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        withContext(Dispatchers.IO) { ProgressIndicatorUtils.awaitWithCheckCanceled(latch) }
        failure?.let { throw it }
    }

    /** Block until the ChangeListManager pipeline is quiescent. Must be called OFF any read/write lock. */
    private suspend fun settle(clm: ChangeListManagerImpl) {
        withContext(Dispatchers.IO) {
            clm.waitForUpdate()
            var guard = 0
            while (clm.isInUpdate() && guard++ < MAX_SETTLE_DRAINS) clm.waitForUpdate()
        }
    }

    /** Build a unified diff for [changes] via the IDE patch builder, capped at [MAX_DIFF_BYTES]. */
    private fun buildUnifiedDiff(project: Project, basePath: String, changes: List<Change>): String {
        if (changes.isEmpty()) return ""
        val patches = IdeaTextPatchBuilder.buildPatch(project, changes, Path.of(basePath), false)
        val writer = StringWriter()
        UnifiedDiffWriter.write(project, patches, writer, "\n", null)
        val diff = writer.toString()
        return if (diff.length > MAX_DIFF_BYTES) {
            diff.substring(0, MAX_DIFF_BYTES) + "\n[... diff truncated at 200 KB ...]"
        } else {
            diff
        }
    }

    /** Map IntelliJ's [Change.Type] to SPEC's vocabulary. */
    private fun mapChangeType(change: Change): String = when (change.type) {
        Change.Type.MODIFICATION -> "MODIFIED"
        Change.Type.NEW -> "ADDED"
        Change.Type.DELETED -> "DELETED"
        Change.Type.MOVED -> "MOVED"
    }

    /** Strip the project base-path prefix to produce a project-relative path. */
    private fun relativize(basePath: String, change: Change): String =
        relativizePath(basePath, ChangesUtil.getFilePath(change).path)

    /** Strip the project base-path prefix from an absolute path to produce a project-relative one. */
    private fun relativizePath(basePath: String, fullPath: String): String =
        if (fullPath.startsWith(basePath)) fullPath.removePrefix(basePath).trimStart('/') else fullPath

    /** Stable token identifying this toolset as the holder of any line-status trackers it requests. */
    private val trackerRequester = Any()

    private companion object {
        const val MAX_DIFF_BYTES = 200 * 1024
        const val MAX_TRACKER_POLLS = 30
        const val TRACKER_POLL_MS = 100L

        /** Bounded drains of trailing updates when settling the changelist pipeline to quiescence. */
        const val MAX_SETTLE_DRAINS = 5

        /** Bounded attempts to verify + reapply a mutation that a trailing update reverted. */
        const val MAX_REAPPLY = 3
    }
}
