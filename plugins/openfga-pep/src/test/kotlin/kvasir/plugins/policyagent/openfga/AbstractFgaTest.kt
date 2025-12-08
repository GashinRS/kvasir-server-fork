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
import kvasir.definitions.kg.PodStore
import kvasir.definitions.kg.PodStoreFactory
import kvasir.definitions.persistence.Sort
import kvasir.utils.test.commons.TestGenerateClientConfig
import kvasir.utils.test.commons.TestPodConfig
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.*

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
    lateinit var podStoreFactory: PodStoreFactory

    @Inject
    lateinit var config: HttpConfig

    protected val testRunId = UUID.randomUUID().toString()
    protected lateinit var podId: String

    @BeforeAll
    fun setup() {
        podId = "${config.baseUri()}${testRunId}"
        // Create a pod store for the test
        val pod = Pod(podId, "{}")
        podStoreFactory.createPodStore().persist(pod).await().indefinitely()
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

internal fun getTokenForClient(clientId: String, clientSecret: String): String {
    val oidcServerUrl = ConfigProvider.getConfig().getValue("kvasir.pod.auth.oidc.server-url", String::class.java)
    val basicAuth = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())
    return given()
        .header(HttpHeaders.AUTHORIZATION, "Basic $basicAuth")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .formParam("grant_type", "client_credentials")
        .post("${oidcServerUrl}/protocol/openid-connect/token")
        .then()
        .statusCode(200)
        .extract()
        .path("access_token")
}