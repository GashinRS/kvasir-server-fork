package kvasir.services.storage.processors.rdf

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import kvasir.definitions.kg.ChangeRecord
import kvasir.definitions.kg.ChangeRecordRequest
import kvasir.definitions.kg.ChangeStatusCode
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.definitions.kg.changes.ChangeHistoryFactory
import kvasir.definitions.kg.changes.ChangeHistoryRequest
import kvasir.definitions.persistence.Sort
import kvasir.definitions.persistence.SortOrder
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.utils.test.clickhouse.ClickhouseTestResource
import kvasir.utils.test.commons.TestHelpers
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.Instant

private const val TEST_FILE_NUMBER_OF_STATEMENTS = 68976

@QuarkusTest
@QuarkusTestResource(ClickhouseTestResource::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RDFStorageMutationListenerTest {

    @Inject
    lateinit var testHelpers: TestHelpers

    @Inject
    lateinit var changeHistoryFactory: ChangeHistoryFactory

    @Inject
    lateinit var kg: KnowledgeGraph

    lateinit var podUri: String

    @Test
    @Order(0)
    fun testMutationListener() {
        podUri = testHelpers.getPodUri()
        // Upload RDF file to S3
        given()
            .contentType(RDFMediaTypes.TURTLE)
            .body(RDFStorageMutationListenerTest::class.java.getResourceAsStream("/SWAPI-WD-data.ttl"))
            .put("$podUri/s3/SWAPI-WD-data.ttl")
            .then().statusCode(200)

        val changeHistoryResult = testHelpers.waitForCondition(
            { changeHistoryFactory.getChangeHistory(podUri).list(ChangeHistoryRequest()) },
            { it.items.isNotEmpty() }).await().indefinitely()
        val changeRequestId = changeHistoryResult.items.first().id
        testHelpers.waitForChangeRequest(changeRequestId, podUri).await()
            .indefinitely()

        // Fetch the inserted records
        val records = insertFileViaS3("SWAPI-WD-data.ttl", RDFMediaTypes.TURTLE)
        assertEquals(TEST_FILE_NUMBER_OF_STATEMENTS, records.size)
    }

    @Test
    @Order(1)
    fun testCanIngestBlankNodes() {
        val records = insertFileViaS3("blank-nodes.nt", RDFMediaTypes.N_TRIPLES)
        val knowsRelation = records.find { it.statement.predicate == "http://example.org/knows" }
        val bobNameRelation =
            records.find { it.statement.predicate == "http://example.org/name" && it.statement.`object` == "Bob" }
        assertEquals(knowsRelation?.statement?.`object`, bobNameRelation?.statement?.subject)
        assertTrue(bobNameRelation?.statement?.subject?.startsWith(podUri) ?: false)
    }

    @Test
    @Order(2)
    fun testCanIngestNonStrictBooleans() {
        val records = insertFileViaS3("non-strict-booleans.nt", RDFMediaTypes.N_TRIPLES)
        assertTrue(records.find { it.statement.predicate == "http://example.org/boolProperty1" }?.statement?.`object`?.let { it as Boolean }
            ?: false)
        assertFalse(records.find { it.statement.predicate == "http://example.org/boolProperty2" }?.statement?.`object`?.let { it as Boolean }
            ?: true)
    }

    private fun insertFileViaS3(fileName: String, rdfType: String): List<ChangeRecord> {
        val podUri = testHelpers.getPodUri()
        val ts = Instant.now()
        // Upload RDF file to S3
        given()
            .contentType(rdfType)
            .body(RDFStorageMutationListenerTest::class.java.getResourceAsStream("/$fileName"))
            .put("$podUri/s3/$fileName")
            .then().statusCode(200)

        val changeHistoryResult = testHelpers.waitForCondition(
            { changeHistoryFactory.getChangeHistory(podUri).find(sort = Sort.by("writeTs", order = SortOrder.DESC)) },
            { result ->
                result.items.any { report ->
                    // Find a changerequest that was queued after the timestamp
                    val matchingReport =
                        report.statusEntry.any { it.timestamp >= ts && it.code == ChangeStatusCode.QUEUED }
                    matchingReport
                }
            })
            .await().indefinitely()
        val changeRequestId = changeHistoryResult.items.first().id

        // Wait for the change request to be processed
        testHelpers.waitForChangeRequest(changeRequestId, podUri).await()
            .indefinitely()

        // Fetch the inserted records
        return kg.streamChangeRecords(ChangeRecordRequest(podUri, changeRequestId, pageSize = 10000)).collect().asList()
            .await()
            .indefinitely()
    }

}