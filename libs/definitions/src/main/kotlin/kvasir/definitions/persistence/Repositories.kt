package kvasir.definitions.persistence

import com.github.f4b6a3.uuid.UuidCreator
import com.github.f4b6a3.uuid.util.UuidUtil
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

    fun <T : PersistentEntity> getVersionedRepository(
        entityClass: KClass<T>,
        podId: String? = null
    ): VersionedRepository<T>
}

/**
 * Generic storage provider for POJOs
 * Could be used for Pods, Change Reports etc.
 * But also to store registered resources for UMA
 */
interface Repository<T : PersistentEntity> {

    /**
     * Find the entity with the specified id.
     *
     * @param id The id of the entity
     */
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

    /**
     * Delete the entity with the specified id.
     *
     * @param id The id of the entity
     */
    fun deleteById(id: String): Uni<Void>

}

/**
 * Generic storage provider for POJOs that supports versioning and tagging of entities.
 * Mainly used for Slices.
 *
 * Note that Kvasir assumes that immutable stores are used, so even the normal Repository will never override state,
 * but instead create a new revision. The VersionedRepository adds the possibility to tag revisions,
 * and to query for revisions based on tags.
 */
interface VersionedRepository<T : PersistentEntity> : Repository<T> {

    fun findById(id: String, tag: String): Uni<T?>

    /**
     * Find the revision that is tagged with the system tag 'default'.
     * If no such tag exists, fallback to the last revision on the main branch.
     */
    fun findDefaultForId(id: String): Uni<T?>

    fun persist(entity: T, tags: Set<String>): Uni<Void>

    fun addTag(id: String, entityTag: EntityTag): Uni<Void>

    fun removeTag(id: String, tag: String): Uni<Void>

    fun listTags(
        id: String,
        limit: Int? = null,
        cursor: String? = null,
        sortByTimestamp: SortOrder = SortOrder.DESC
    ): Uni<PagedResult<EntityTag>>

    /**
     * Get the lineage of the specified revisions, i.e. for each revision, find the tagged revision that is the closest
     * ancestor on the same branch (if any), and return the number of revisions between them.
     */
    fun getLineage(entities: List<T>): Uni<Map<T, RevisionLineage?>>

}

interface RepositoriesLifecycleManager {

    fun initialize(detectedEntities: Set<Class<out PersistentEntity>>): Uni<Void>

    fun initializeForPod(podId: String, detectedEntities: Set<Class<out PersistentEntity>>): Uni<Void>

    fun cleanup(detectedEntities: Set<Class<out PersistentEntity>>): Uni<Void>

    fun cleanupForPod(podId: String, detectedEntities: Set<Class<out PersistentEntity>>): Uni<Void>

}

abstract class PersistentEntity {
    abstract val id: String
    abstract val createdBy: String

    /**
     * Property that should be used by the persistence implementation to track the version and the time the state was written.
     */
    var revisionId: String? = null

    /**
     * Property that should be used by the persistence implementation to track the revision this revision is based on.
     * (allows tracking lineage)
     */
    var parentRevisionId: String? = null

    /**
     * Property that should be used by the persistence implementation to track the branch this revision belongs to. (allows tracking lineage and branching)
     */
    var branch: String? = null

    fun getLastModifiedAt(): Instant? {
        return revisionId?.let {
            val uuid = UuidCreator.fromString(it)
            UuidUtil.getInstant(uuid)
        }
    }
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

data class EntityTag(
    var id: String? = null, // Used by API to return an identifier
    val revisionId: String,
    val tag: String,
    val createdAt: Instant,
    val createdBy: String
) {
    constructor(revisionId: String, tag: String, createdAt: Instant, createdBy: String) : this(
        null,
        revisionId,
        tag,
        createdAt,
        createdBy
    )
}

data class RevisionLineage(
    val entityId: String,
    val revisionId: String,
    val tags: Set<String>,
    val taggedRevisionId: String,
    val numberOfRevisionsAhead: Int
)