package kvasir.plugins.kg.clickhouse

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import kvasir.definitions.kg.Pod
import kvasir.definitions.rdf.KvasirVocab
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.utils.databaseFromPodId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.*

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestClickhousePodStore {

    @Inject
    lateinit var podStoreFactory: ClickhousePodStoreFactory

    @Inject
    lateinit var clichouseInitializer: ClickhouseInitializer

    @Inject
    lateinit var clickhouseClient: ClickhouseClient

    private val testRunId = UUID.randomUUID().toString()

    @BeforeAll
    fun setup() {
        clichouseInitializer.init().await().indefinitely()
    }

    @Test
    fun testInsertAndQuery() {
        val podStore = podStoreFactory.createPodStore()

        // Generate some pods
        val pods = (1..10).map { i ->
            Pod(
                "http://example.com/pod$i",
                mapOf(KvasirVocab.autoIngestRDF to true, "http://example.org/testRunId" to testRunId)
            )
        }

        // Insert them
        pods.forEach {
            podStore.persist(it).await().indefinitely()
        }

        // Query them back
        val retrievedPods = podStore.find().await()
            .indefinitely().items.filter { it.configuration["http://example.org/testRunId"] == testRunId }
        assertEquals(pods.map { it.id }.toSet(), retrievedPods.map { it.id }.toSet())

        // Clean up
        pods.forEach {
            podStore.deleteById(it.id, true).await().indefinitely()
        }

        val retrievedPodsAfterDelete = podStore.find().await()
            .indefinitely().items.filter { it.configuration["http://example.org/testRunId"] == testRunId }
        assertEquals(0, retrievedPodsAfterDelete.size)
    }

}