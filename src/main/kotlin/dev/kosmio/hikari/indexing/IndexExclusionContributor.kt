package dev.kosmio.hikari.indexing

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.RootsChangeRescanningInfo
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.workspace.jps.entities.ContentRootEntity
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.workspaceModel.core.fileIndex.WorkspaceFileIndexContributor
import com.intellij.workspaceModel.core.fileIndex.WorkspaceFileSetExclusionCondition
import com.intellij.workspaceModel.core.fileIndex.WorkspaceFileSetRegistrar

/**
 * Excludes, under every content root, what the global and project exclusion patterns match.
 *
 * The file index evaluates the condition at lookup time rather than when it is registered, so a directory
 * created after the project opened is excluded as soon as it exists, and an edited pattern list applies to
 * every root at once; [rescan] makes the indexes catch up with an edit.
 */
class IndexExclusionContributor : WorkspaceFileIndexContributor<ContentRootEntity> {

    override val entityClass: Class<ContentRootEntity> get() = ContentRootEntity::class.java

    override fun registerFileSets(entity: ContentRootEntity, registrar: WorkspaceFileSetRegistrar, storage: EntityStorage) {
        registrar.registerExclusionCondition(entity.url, PatternExclusion, entity)
    }

    companion object {
        /** Re-applies the patterns to [project]'s file index and indexes, after either list changed. */
        fun rescan(project: Project) {
            if (project.isDisposed) return
            WriteAction.run<RuntimeException> {
                ProjectRootManagerEx.getInstanceEx(project)
                    .makeRootsChange(EmptyRunnable.getInstance(), RootsChangeRescanningInfo.TOTAL_RESCAN)
            }
        }
    }
}

/**
 * Asks the patterns about a file. The file index calls it on the file and on each of its ancestors up to the
 * content root, for every lookup, so it reads only the compiled patterns and does no I/O.
 *
 * The contributor extension point is application-wide and is handed no project, so the condition resolves
 * one from the file: the open project whose directory most closely contains it. Paths are matched relative
 * to that directory; a file under no open project's directory is never excluded.
 *
 * Stateless, so every registration is equal to every other and an edited list never reads to the file index
 * as a changed file set.
 */
private data object PatternExclusion : WorkspaceFileSetExclusionCondition {

    override fun shouldExclude(file: VirtualFile): Boolean {
        val global = GlobalIndexExclusions.getInstance().compiled
        val path = file.path
        var owner: Project? = null
        var ownerBase = ""
        for (project in ProjectManager.getInstance().openProjects) {
            val base = project.basePath ?: continue
            if (base.length > ownerBase.length && isStrictlyUnder(path, base)) {
                owner = project
                ownerBase = base
            }
        }
        if (owner == null || owner.isDisposed) return false
        val own = ProjectIndexExclusions.getInstance(owner).compiled
        if (global.isEmpty() && own.isEmpty()) return false
        val relative = path.substring(ownerBase.length + 1)
        val isDirectory = file.isDirectory
        return global.matches(relative, isDirectory) || own.matches(relative, isDirectory)
    }

    private fun isStrictlyUnder(path: String, base: String): Boolean =
        path.length > base.length + 1 && path.startsWith(base) && path[base.length] == '/'
}
