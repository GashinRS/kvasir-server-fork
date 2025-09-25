package kvasir.plugins.policyagent.openfga

import idlab.quarkus.ext.pep.openfga.runtime.OpenFgaManager
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kvasir.definitions.auth.AuthInitializer
import kvasir.definitions.config.KvasirConfig
import kvasir.definitions.kg.PagedResult
import kvasir.definitions.kg.Pod
import kvasir.definitions.kg.PodStore
import kvasir.definitions.kg.PodStoreFactory
import kvasir.definitions.persistence.Sort
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractFgaTest {

    @Inject
    lateinit var authInitializer: AuthInitializer

    @Inject
    lateinit var fgaManager: OpenFgaManager

    @Inject
    lateinit var podStoreFactory: PodStoreFactory

    @ConfigProperty(name = KvasirConfig.BASE_URI_PROPERTY)
    lateinit var baseUri: String

    protected val testRunId = UUID.randomUUID().toString()
    protected lateinit var podId : String

    @BeforeAll
    fun setup() {
        podId = "${baseUri}${testRunId}"
        // Create a pod store for the test
        val pod = Pod(podId, mapOf())
        podStoreFactory.createPodStore().persist(pod).await().indefinitely()
        // Init openfga-policy-agent
        authInitializer.initialize().chain { _ ->
            authInitializer.initializeForPod(podId, testRunId, "alice", pod)
        }.await().indefinitely()
    }

    @AfterAll
    fun teardown() {
        podStoreFactory.createPodStore().deleteById(podId, true).await().indefinitely()
        authInitializer.cleanupForPod(podId, testRunId).await().indefinitely()
    }

}

@ApplicationScoped
class MockPodStoreFactory : PodStoreFactory {
    private val store = MockPodStore()
    override fun createPodStore(): PodStore {
        return store
    }

}


class MockPodStore : PodStore {
    private val pods = mutableMapOf<String, Pod>()
    override fun persist(entity: Pod): Uni<Void> {
        pods[entity.id] = entity
        return Uni.createFrom().voidItem()
    }

    override fun find(
        filter: String?,
        limit: Int?,
        cursor: String?,
        sort: Sort
    ): Uni<PagedResult<Pod>> {
        return Uni.createFrom().item(PagedResult(pods.values.toList()))
    }

    override fun findById(id: String): Uni<Pod?> {
        return Uni.createFrom().item(pods[id])
    }

    override fun deleteById(id: String): Uni<Void> {
        pods.remove(id)
        return Uni.createFrom().voidItem()
    }

    override fun deleteById(id: String, deleteData: Boolean): Uni<Void> {
        return deleteById(id)
    }

}