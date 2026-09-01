package dev.kosmio.mcppowerup

import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesUtil

/** Project-relative path helpers, shared by every toolset this plugin contributes. */
object ProjectPaths {

    /** Strip the project base-path prefix from an absolute path to produce a project-relative one. */
    fun relativize(basePath: String, fullPath: String): String =
        if (fullPath.startsWith(basePath)) fullPath.removePrefix(basePath).trimStart('/') else fullPath

    /** The project-relative path a [Change] touches. */
    fun relativize(basePath: String, change: Change): String =
        relativize(basePath, ChangesUtil.getFilePath(change).path)
}
