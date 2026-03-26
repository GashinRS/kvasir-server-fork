package kvasir.definitions.persistence

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import kvasir.definitions.kg.PagedResult
import kvasir.definitions.reactive.skipToLast
import java.time.Instant
import kotlin.reflect.KClass

interface RepositoryFactory {
    /**
     * Get a repository for accessing and managing persistent entities.
     *
     * @param entityClass The KClass of the persistent entity type.
     * @param podId Optional pod identifier for per-pod storage. If the entity is annotated for per-pod storage, this must be provided.
     *
     * @return A Repository instance for the specified entity type.
     */
    fun <T : PersistentEntity> getRepository(
        entityClass: KClass<T>,
        podId: String? = null
    ): Repository<T>
}

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

    fun persist(entities: List<T>): Uni<Void> =
        Multi.createFrom().iterable(entities).onItem().transformToUni { persist(it) }.merge(4).skipToLast()

    fun deleteById(id: String): Uni<Void>

}

interface RepositoriesLifecycleManager {

    fun initialize(detectedEntities: Set<Class<out PersistentEntity>>): Uni<Void>

    fun initializeForPod(podId: String, detectedEntities: Set<Class<out PersistentEntity>>): Uni<Void>

    fun cleanup(detectedEntities: Set<Class<out PersistentEntity>>): Uni<Void>

    fun cleanupForPod(podId: String, detectedEntities: Set<Class<out PersistentEntity>>): Uni<Void>

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