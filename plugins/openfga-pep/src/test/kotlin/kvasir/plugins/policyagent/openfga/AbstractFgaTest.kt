package kvasir.plugins.policyagent.openfga

import idlab.quarkus.ext.pep.openfga.runtime.cdi.OpenFgaManager
import io.restassured.RestAssured.given
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.config.HttpConfig
import kvasir.definitions.kg.PagedResult
import kvasir.definitions.kg.Pod
import kvasir.definitions.persistence.PersistentEntity
import kvasir.definitions.persistence.Repository
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.persistence.Sort
import kvasir.utils.test.commons.TestGenerateClientConfig
import kvasir.utils.test.commons.TestPodConfig
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.*
import kotlin.reflect.KClass

const val ALICE_CLIENT_NAME = "alice-client"
const val ALICE_CLIENT_SECRET = "alice"
const val BOB_CLIENT_NAME = "bob-client"
const val BOB_CLIENT_SECRET = "bob"

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractFgaTest {

    @Inject
    lateinit var authInitializer: OpenFgaInitializer

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
        val pod = Pod(podId, "{}")
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

    private val mockRepositories: MutableMap<Pair<KClass<*>, String?>, MockRepository<*>> = mutableMapOf()

    override fun <T : PersistentEntity> getRepository(
        entityClass: KClass<T>,
        podId: String?
    ): Repository<T> {
        val repositoryKey = entityClass to podId
        if (!mockRepositories.containsKey(repositoryKey)) {
            mockRepositories.put(repositoryKey, MockRepository<T>())
        }
        return mockRepositories[repositoryKey] as Repository<T>
    }

}


class MockRepository<T : PersistentEntity> : Repository<T> {
    private val pods = mutableMapOf<String, T>()
    override fun persist(entity: T): Uni<Void> {
        pods[entity.id] = entity
        return Uni.createFrom().voidItem()
    }

    override fun find(
        filter: String?,
        limit: Int?,
        cursor: String?,
        sort: Sort
    ): Uni<PagedResult<T>> {
        return Uni.createFrom().item(PagedResult(pods.values.toList()))
    }

    override fun findById(id: String): Uni<T?> {
        return Uni.createFrom().item(pods[id])
    }

    override fun deleteById(id: String): Uni<Void> {
        pods.remove(id)
        return Uni.createFrom().voidItem()
    }

}