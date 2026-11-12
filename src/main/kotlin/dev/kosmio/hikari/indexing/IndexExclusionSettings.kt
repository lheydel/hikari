package dev.kosmio.hikari.indexing

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * What a list of exclusion patterns persists: the patterns one per line, and whether the list was ever set.
 *
 * [customized] is what keeps a set list from serializing to nothing: the serializer drops an empty [patterns]
 * as a default value, and a component that serializes to nothing leaves its previous element in the file, so
 * an emptied list would load its old patterns.
 */
class IndexExclusionState {
    var customized: Boolean = false
    var patterns: String = ""
}

/** One persisted list of exclusion patterns and its compiled form, which the file index reads on every lookup. */
abstract class IndexExclusionList : PersistentStateComponent<IndexExclusionState> {

    private var state = IndexExclusionState()

    @Volatile
    internal var compiled: ExclusionPatterns = ExclusionPatterns.NONE
        private set

    var patterns: List<String>
        get() = ExclusionPatterns.parseLines(state.patterns)
        set(value) {
            state = IndexExclusionState().also {
                it.customized = true
                it.patterns = value.joinToString("\n")
            }
            compiled = ExclusionPatterns.of(value)
        }

    override fun getState(): IndexExclusionState = state

    override fun loadState(loaded: IndexExclusionState) {
        state = loaded
        compiled = ExclusionPatterns.of(patterns)
    }
}

/** The patterns excluded in every project. */
@Service(Service.Level.APP)
@State(name = "HikariIndexExclusions", storages = [Storage("hikari.xml")])
class GlobalIndexExclusions : IndexExclusionList() {
    companion object {
        fun getInstance(): GlobalIndexExclusions = service()
    }
}

/** The patterns excluded in one project on top of the global ones, kept in the per-user workspace file. */
@Service(Service.Level.PROJECT)
@State(name = "HikariIndexExclusions", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ProjectIndexExclusions : IndexExclusionList() {
    companion object {
        fun getInstance(project: Project): ProjectIndexExclusions = project.service()
    }
}
