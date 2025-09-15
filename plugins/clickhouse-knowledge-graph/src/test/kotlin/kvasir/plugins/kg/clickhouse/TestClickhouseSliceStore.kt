package kvasir.plugins.kg.clickhouse

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.rdf.KvasirVocab
import kvasir.utils.test.clickhouse.ClickhouseTestResource
import kvasir.utils.test.commons.TestConstants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@QuarkusTestResource(ClickhouseTestResource::class)
class TestClickhouseSliceStore {


    @Inject
    lateinit var sliceStoreFactory: ClickhouseSliceStoreFactory

    @Inject
    lateinit var clichouseInitializer: ClickhouseInitializer

    @BeforeAll
    fun setup() {
        clichouseInitializer.initializePodSchema(TestConstants.TEST_POD_1_ID).await().indefinitely()
    }

    @Test
    fun testInsertAndQuery() {
        val sliceStore = sliceStoreFactory.getSliceStore(TestConstants.TEST_POD_1_ID)

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