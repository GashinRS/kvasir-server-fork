package kvasir.plugins.policyagent.openfga

import idlab.quarkus.ext.pep.openfga.runtime.OpenFgaManager
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kvasir.definitions.auth.AuthInitializer
import kvasir.definitions.config.KvasirConfig
import kvasir.definitions.kg.Pod
import kvasir.definitions.kg.PodStore
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractFgaTest {

    @Inject
    lateinit var authInitializer: AuthInitializer

    @Inject
    lateinit var fgaManager: OpenFgaManager

    @Inject
    lateinit var podStore: PodStore

    @ConfigProperty(name = KvasirConfig.BASE_URI_PROPERTY)
    lateinit var baseUri: String

    @BeforeAll
    fun setup() {
        val podId = "${baseUri}alice"
        // Create a pod store for the test
        val pod = Pod(podId, mapOf())
        podStore.persist(pod).await().indefinitely()
        // Init openfga-policy-agent
        authInitializer.initialize().chain { _ ->
            authInitializer.initializeForPod(podId, "alice", "alice", pod)
        }.await().indefinitely()
    }

}

@ApplicationScoped
class MockPodStore : PodStore {
    private val pods = mutableMapOf<String, Pod>()
    override fun persist(pod: Pod): Uni<Void> {
        pods[pod.id] = pod
        return Uni.createFrom().voidItem()
    }

    override fun list(): Uni<List<Pod>> {
        return Uni.createFrom().item(pods.values.toList())
    }

    override fun getById(id: String): Uni<Pod?> {
        return Uni.createFrom().item(pods[id])
    }

    override fun deleteById(id: String): Uni<Void> {
        pods.remove(id)
        return Uni.createFrom().voidItem()
    }

}