package kvasir.plugins.kg.clickhouse

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.rdf.KvasirVocab
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.utils.databaseFromPodId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestClickhouseSliceStore {


    @Inject
    lateinit var repositoryFactory: RepositoryFactory

    @Inject
    lateinit var clichouseInitializer: ClickhouseLifecycleManager

    @Inject
    lateinit var clickhouseClient: ClickhouseClient

    private val testRunId = UUID.randomUUID().toString()

    @BeforeAll
    fun setup() {
        clichouseInitializer.initializeForPod(testRunId, setOf(Slice::class.java)).await().indefinitely()
    }

    @AfterAll
    fun teardown() {
        clickhouseClient.execute("DROP DATABASE IF EXISTS `${databaseFromPodId(testRunId)}`").await().indefinitely()
    }

    @Test
    fun testInsertAndQuery() {
        val sliceStore = repositoryFactory.getRepository(Slice::class, testRunId)

        // Insert some slices
        val slices = (1..10).map { i ->
            Slice(
                "http://example.com/slice$i",
                mapOf(
                    "ex" to "http://example.org/",
                    "kss" to KvasirVocab.baseUri,
                ),
                author = "alice",
                "Test Slice $i",
                "",
                "type Query { someField: String }",
                false,
                if (i % 2 == 0) emptySet() else setOf("http://example.org/Graph")
            )
        }

        slices.forEach {
            sliceStore.persist(it).await().indefinitely()
        }

        // Query them back
        val retrievedSlices = sliceStore.find().await().indefinitely()
        assertEquals(slices.sortedBy { it.id }, retrievedSlices.items.sortedBy { it.id })

        // Test get by id
        val selectedSlice = slices.random()
        val retrievedSlice = sliceStore.findById(selectedSlice.id).await().indefinitely()
        assertEquals(selectedSlice, retrievedSlice)

        // Clean up
        slices.forEach {
            sliceStore.deleteById(it.id).await().indefinitely()
        }

        val retrievedPodsAfterDelete = sliceStore.find().await().indefinitely()
        assertEquals(0, retrievedPodsAfterDelete.items.size)
    }

}