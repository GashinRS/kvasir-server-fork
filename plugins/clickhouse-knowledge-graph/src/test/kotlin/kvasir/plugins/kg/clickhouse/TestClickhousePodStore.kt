package kvasir.plugins.kg.clickhouse

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import kvasir.definitions.kg.Pod
import kvasir.definitions.rdf.KvasirVocab
import kvasir.utils.test.clickhouse.ClickhouseTestResource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@QuarkusTestResource(ClickhouseTestResource::class)
class TestClickhousePodStore {

    @Inject
    lateinit var podStoreFactory: ClickhousePodStoreFactory

    @Inject
    lateinit var clichouseInitializer: ClickhouseInitializer

    @BeforeAll
    fun setup() {
        clichouseInitializer.init().await().indefinitely()
    }

    @Test
    fun testInsertAndQuery() {
        val podStore = podStoreFactory.createPodStore()

        // Generate some pods
        val pods = (1..10).map { i ->
            Pod("http://example.com/pod$i", mapOf(KvasirVocab.autoIngestRDF to true))
        }

        // Insert them
        pods.forEach {
            podStore.persist(it).await().indefinitely()
        }

        // Query them back
        val retrievedPods = podStore.find().await().indefinitely()
        assertEquals(pods.map { it.id }.toSet(), retrievedPods.items.map { it.id }.toSet())

        // Clean up
        pods.forEach {
            podStore.deleteById(it.id).await().indefinitely()
        }

        val retrievedPodsAfterDelete = podStore.find().await().indefinitely()
        assertEquals(0, retrievedPodsAfterDelete.items.size)
    }

}