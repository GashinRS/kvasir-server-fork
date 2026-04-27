package kvasir.plugins.policyagent.openfga

import com.github.f4b6a3.uuid.UuidCreator
import idlab.quarkus.ext.pep.openfga.runtime.cdi.OpenFgaManager
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kvasir.definitions.config.HttpConfig
import kvasir.definitions.kg.PagedResult
import kvasir.definitions.kg.Pod
import kvasir.definitions.persistence.*
import kvasir.utils.test.commons.TestGenerateClientConfig
import kvasir.utils.test.commons.TestPodConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

const val ALICE_CLIENT_NAME = "alice-client"
const val ALICE_CLIENT_SECRET = "alice"
const val BOB_CLIENT_NAME = "bob-client"
const val BOB_CLIENT_SECRET = "bob"

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractFgaTest {

    @Inject
    lateinit var authInitializer: OpenFgaLifecycleManager

    @Inject
    lateinit var fgaManager: OpenFgaManager

    @Inject
    lateinit var repositoryFactory: RepositoryFactory

    @Inject
    lateinit var config: HttpConfig

    protected val testRunId = UUID.randomUUID().toString()
    protected lateinit var podId: String

    @BeforeAll
    fun setup() {
        podId = "${config.baseUri()}${testRunId}"
        // Create a pod store for the test
        val pod = Pod(podId, "system","{}")
        repositoryFactory.getRepository(Pod::class).persist(pod).await().indefinitely()
        // Init openfga-policy-agent
        authInitializer.initialize().chain { _ ->
            authInitializer.initializeForPod(
                pod, TestPodConfig(
                    testRunId, "alice", listOf(
                        TestGenerateClientConfig(ALICE_CLIENT_NAME, ALICE_CLIENT_SECRET, true),
                        TestGenerateClientConfig(BOB_CLIENT_NAME, BOB_CLIENT_SECRET, false)
                    )
                )
            )
        }.await().indefinitely()
    }

    @AfterAll
    fun teardown() {
        repositoryFactory.getRepository(Pod::class).deleteById(podId).await().indefinitely()
        authInitializer.cleanupForPod(podId, testRunId).await().indefinitely()
    }

}

@ApplicationScoped
class MockPodStoreFactory : RepositoryFactory {

    override fun <T : PersistentEntity> getRepository(
        entityClass: KClass<T>,
        podId: String?
    ): Repository<T> = InMemoryRepository()

    override fun <T : PersistentEntity> getVersionedRepository(
        entityClass: KClass<T>,
        podId: String?
    ): VersionedRepository<T> = InMemoryVersionedRepository()

}

/**
 * Simple in-memory [Repository] backed by a map of id → latest entity.
 * Revisions are appended to a list; [findById] always returns the last one.
 * The RSQL [filter] parameter of [find] is ignored for simplicity.
 */
private open class InMemoryRepository<T : PersistentEntity> : Repository<T> {

    // id → ordered list of (revisionId, entity)
    protected val revisions: MutableMap<String, MutableList<Pair<String, T>>> = ConcurrentHashMap()

    protected fun latestRevision(id: String): T? =
        revisions[id]?.lastOrNull()?.second

    override fun findById(id: String): Uni<T?> =
        Uni.createFrom().item(latestRevision(id))

    override fun find(filter: String?, limit: Int?, cursor: String?, sort: Sort): Uni<PagedResult<T>> {
        val items = revisions.values
            .mapNotNull { it.lastOrNull()?.second }
            .sortedBy { it.id }
        return Uni.createFrom().item(PagedResult(items))
    }

    override fun persist(entity: T): Uni<Void> {
        entity.revisionId = UuidCreator.getTimeOrderedEpoch().toString()
        revisions.getOrPut(entity.id) { mutableListOf() }
            .add(entity.revisionId!! to entity)
        return Uni.createFrom().voidItem()
    }

    override fun deleteById(id: String): Uni<Void> {
        revisions.remove(id)
        return Uni.createFrom().voidItem()
    }
}

/**
 * In-memory [VersionedRepository] that extends [InMemoryRepository] with a
 * simple tags map: id → tagName → revisionId.
 */
private class InMemoryVersionedRepository<T : PersistentEntity>
    : InMemoryRepository<T>(), VersionedRepository<T> {

    // id → tagName → revisionId
    private val tags: MutableMap<String, MutableMap<String, String>> = ConcurrentHashMap()

    private fun revisionById(id: String, revisionId: String): T? =
        revisions[id]?.firstOrNull { it.first == revisionId }?.second

    override fun findById(id: String, tag: String): Uni<T?> {
        val revisionId = tags[id]?.get(tag)
        return Uni.createFrom().item(revisionId?.let { revisionById(id, it) })
    }

    override fun findDefaultForId(id: String): Uni<T?> =
        findById(id, "default").chain { tagged ->
            if (tagged != null) Uni.createFrom().item(tagged)
            else findById(id)
        }

    override fun persist(entity: T, tags: Set<String>): Uni<Void> =
        persist(entity).invoke { _: Void? ->
            val revisionId = entity.revisionId!!
            tags.forEach { tag ->
                this.tags.getOrPut(entity.id) { ConcurrentHashMap() }[tag] = revisionId
            }
        }

    override fun addTag(id: String, entityTag: EntityTag): Uni<Void> {
        tags.getOrPut(id) { ConcurrentHashMap() }[entityTag.tag] = entityTag.revisionId
        return Uni.createFrom().voidItem()
    }

    override fun removeTag(id: String, tag: String): Uni<Void> {
        tags[id]?.remove(tag)
        return Uni.createFrom().voidItem()
    }

    override fun listTags(
        id: String,
        limit: Int?,
        cursor: String?,
        sortByTimestamp: SortOrder
    ): Uni<PagedResult<EntityTag>> {
        val items = tags[id]
            ?.entries
            ?.map { (tag, revisionId) ->
                EntityTag(
                    revisionId = revisionId,
                    tag = tag,
                    createdAt = Instant.now(),
                    createdBy = revisionById(id, revisionId)?.createdBy ?: ""
                )
            }
            ?.sortedWith(compareBy { it.revisionId })
            ?.let { if (sortByTimestamp == SortOrder.DESC) it.reversed() else it }
            ?: emptyList()
        return Uni.createFrom().item(PagedResult(items))
    }

    override fun getLineage(entities: List<T>): Uni<Map<T, RevisionLineage?>> {
        // For each entity, walk backwards through the revision list on its branch to find
        // the nearest tagged revision at or before the entity's own revision.
        val result = entities.associateWith { entity ->
            val id = entity.id
            val entityRevId = entity.revisionId ?: return@associateWith null
            val branch = entity.branch ?: "main"

            // All revisions for this entity on the same branch, ordered chronologically
            val branchRevisions = revisions[id]
                ?.filter { (_, e) -> (e.branch ?: "main") == branch }
                ?: return@associateWith null

            // Index of the entity's revision in the ordered list
            val entityIndex = branchRevisions.indexOfFirst { (revId, _) -> revId == entityRevId }
            if (entityIndex < 0) return@associateWith null

            // Revisions at or before the entity's revision (inclusive)
            val candidateRevisions = branchRevisions.subList(0, entityIndex + 1)

            // All tags for this entity: tagName → revisionId
            val entityTags = tags[id] ?: emptyMap<String, String>()
            // Invert: revisionId → set of tag names
            val tagsByRevision = entityTags.entries
                .groupBy({ it.value }, { it.key })

            // Find the closest (most recent) tagged revision at or before the current one
            val bestEntry = candidateRevisions.lastOrNull { (revId, _) -> tagsByRevision.containsKey(revId) }
                ?: return@associateWith null

            val (taggedRevId, _) = bestEntry
            val tagsAtBest = tagsByRevision[taggedRevId]!!.toSet()
            val numberOfRevisionsAhead = entityIndex - branchRevisions.indexOfFirst { (revId, _) -> revId == taggedRevId }

            RevisionLineage(
                entityId = id,
                revisionId = entityRevId,
                tags = tagsAtBest,
                taggedRevisionId = taggedRevId,
                numberOfRevisionsAhead = numberOfRevisionsAhead
            )
        }
        return Uni.createFrom().item(result)
    }
}