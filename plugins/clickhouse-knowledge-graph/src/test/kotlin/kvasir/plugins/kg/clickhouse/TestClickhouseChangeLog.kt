package kvasir.plugins.kg.clickhouse

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import kvasir.definitions.kg.ChangeStatusCode
import kvasir.definitions.kg.changes.ChangeReport
import kvasir.definitions.kg.changes.ChangeReportStatusEntry
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.persistence.Sort
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.utils.databaseFromPodId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.random.Random

private val sliceRefs = setOf(null, "http://example.org/someSlice1", "http://example.org/someSlice2")

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestClickhouseChangeLog {


    @Inject
    lateinit var repositoryFactory: RepositoryFactory

    @Inject
    lateinit var clichouseInitializer: ClickhouseLifecycleManager

    @Inject
    lateinit var clickhouseClient: ClickhouseClient

    private val testRunId = UUID.randomUUID().toString()

    @BeforeAll
    fun setup() {
        clichouseInitializer.initializePodSchema(testRunId, setOf(ChangeReport::class.java)).await().indefinitely()
    }

    @AfterAll
    fun teardown() {
        clickhouseClient.execute("DROP DATABASE IF EXISTS ${databaseFromPodId(testRunId)}").await().indefinitely()
    }

    @Test
    fun testInsertAndQuery() {
        val changeHistory = repositoryFactory.getRepository(ChangeReport::class, testRunId)

        // Insert some history entries
        val historyRecords = (1..10).map { i ->
            val error = i == 1 || i == 5
            ChangeReport(
                id = "http://example.com/change$i",
                requestingUser = "alice",
                podId = testRunId,
                statusEntry = listOf(
                    ChangeReportStatusEntry(
                        Instant.now().minus(Random.nextLong(15), ChronoUnit.MILLIS),
                        ChangeStatusCode.QUEUED
                    ),
                    ChangeReportStatusEntry(
                        Instant.now(),
                        if (error) ChangeStatusCode.INTERNAL_ERROR else ChangeStatusCode.COMMITTED
                    )
                ),
                sliceId = sliceRefs.random(),
                nrOfInserts = Random.nextLong(500),
                nrOfDeletes = Random.nextLong(500),
                errorMessage = "Some error message".takeIf { error }
            )
        }

        historyRecords.forEach {
            changeHistory.persist(it).await().indefinitely()
        }

        // Query them back
        val retrievedRecords = changeHistory.find().await().indefinitely()
        assertEquals(historyRecords.sortedBy { it.id }, retrievedRecords.items.sortedBy { it.id })

        // Test filter and ordering
        val selectedSliceId = sliceRefs.filterNotNull().random()
        val filteredRecords =
            changeHistory.find("sliceId=='$selectedSliceId'", sort = Sort.descending("writeTs")).await().indefinitely()
        val expectedFilteredRecords = historyRecords.filter { it.sliceId == selectedSliceId }
            .sortedByDescending { it.writeTs }
        assertEquals(expectedFilteredRecords, filteredRecords.items)

        // Test ordering by POJO field
        val orderedRecords = changeHistory.find(sort = Sort.ascending("nrOfInserts", "id")).await().indefinitely()
        val expectedOrderedRecords = historyRecords.sortedWith(
            compareBy<ChangeReport> { it.nrOfInserts }.thenBy { it.id }
        )
        assertEquals(expectedOrderedRecords, orderedRecords.items)

        // Test get by id
        val selectedRecord = historyRecords.random()
        val retrievedRecord = changeHistory.findById(selectedRecord.id).await().indefinitely()
        assertEquals(selectedRecord, retrievedRecord)

        // Clean up
        historyRecords.forEach {
            changeHistory.deleteById(it.id).await().indefinitely()
        }

        val retrievedPodsAfterDelete = changeHistory.find().await().indefinitely()
        assertEquals(0, retrievedPodsAfterDelete.items.size)
    }

}