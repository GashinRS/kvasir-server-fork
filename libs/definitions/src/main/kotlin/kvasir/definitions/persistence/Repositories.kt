package kvasir.definitions.persistence

import io.smallrye.mutiny.Uni
import kvasir.definitions.kg.PagedResult
import java.time.Instant

/**
 * Generic storage provider for POJOs
 * Could be used for Pods, Slices, etc.
 * But also to store registered resources for UMA
 */
interface Repository<T : PersistentEntity> {

    fun findById(id: String): Uni<T?>

    fun find(
        filter: String? = null,
        limit: Int? = null,
        cursor: String? = null,
        sort: Sort = Sort.ascending("id")
    ): Uni<PagedResult<T>>

    fun persist(entity: T): Uni<Void>

    fun deleteById(id: String): Uni<Void>

}

abstract class PersistentEntity {
    abstract val id: String

    /**
     * Property that should be used by the persistence implementation to track the time the state was written.
     */
    var writeTs: Instant? = null
}

data class Sort(
    val columns: List<SortColumn>
) {
    companion object {
        fun by(vararg columns: String, order: SortOrder): Sort {
            return Sort(columns.map { SortColumn(it, order) })
        }

        fun ascending(vararg columns: String): Sort {
            return by(*columns, order = SortOrder.ASC)
        }

        fun descending(vararg columns: String): Sort {
            return by(*columns, order = SortOrder.DESC)
        }
    }

    fun thenBy(column: String, order: SortOrder): Sort {
        return Sort(columns + SortColumn(column, order))
    }

}

data class SortColumn(val name: String, val direction: SortOrder)
enum class SortOrder {
    ASC, DESC
}