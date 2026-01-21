package kvasir.plugins.kg.clickhouse

import io.quarkus.test.junit.QuarkusTest
import io.vertx.core.json.Json
import jakarta.inject.Inject
import kvasir.definitions.kg.Pod
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.rdf.KvasirVocab
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.*

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestClickhousePodStore {

    @Inject
    lateinit var repositoryFactory: RepositoryFactory

    @Inject
    lateinit var clickhouseLifecycleManager: ClickhouseLifecycleManager

    private val testRunId = UUID.randomUUID().toString()

    @BeforeAll
    fun setup() {
        clickhouseLifecycleManager.init(setOf(Pod::class.java)).await().indefinitely()
    }

    @Test
    fun testInsertAndQuery() {
        val podStore = repositoryFactory.getRepository(Pod::class)

        // Generate some pods
        val pods = (1..10).map { i ->
            Pod(
                "http://example.com/pod$i",
                Json.encode(mapOf(KvasirVocab.autoIngestRDF to true, "http://example.org/testRunId" to testRunId))
            )
        }

        // Insert them
        pods.forEach {
            podStore.persist(it).await().indefinitely()
        }

        // Query them back
        val retrievedPods = podStore.find().await()
            .indefinitely().items.filter { it.getConfigAsJson()["http://example.org/testRunId"] == testRunId }
        assertEquals(pods.map { it.id }.toSet(), retrievedPods.map { it.id }.toSet())

        // Clean up
        pods.forEach {
            podStore.deleteById(it.id).await().indefinitely()
            clickhouseLifecycleManager.dropPodDatabase(it.id).await().indefinitely()
        }

        val retrievedPodsAfterDelete = podStore.find().await()
            .indefinitely().items.filter { it.getConfigAsJson()["http://example.org/testRunId"] == testRunId }
        assertEquals(0, retrievedPodsAfterDelete.size)
    }

}