package kvasir.services.storage.processors.rdf

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.vertx.core.json.JsonObject
import jakarta.inject.Inject
import kvasir.definitions.kg.ChangeRecord
import kvasir.definitions.kg.ChangeRecordRequest
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.definitions.kg.changes.ChangeHistoryFactory
import kvasir.definitions.kg.changes.ChangeReport
import kvasir.definitions.persistence.Sort
import kvasir.definitions.persistence.SortOrder
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.utils.test.commons.AbstractPodTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant

private const val TEST_FILE_NUMBER_OF_STATEMENTS = 68976

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RDFStorageMutationListenerTest : AbstractPodTest() {

    @Inject
    lateinit var changeHistoryFactory: ChangeHistoryFactory

    @Inject
    lateinit var kg: KnowledgeGraph

    @Test
    fun testMutationListener() {
        // Fetch the inserted records
        val records = insertFileViaS3("SWAPI-WD-data.ttl", RDFMediaTypes.TURTLE)
        assertEquals(TEST_FILE_NUMBER_OF_STATEMENTS, records.size)
        // Delete the file
        deleteFileFromS3("SWAPI-WD-data.ttl")
    }

    @Test
    fun testCanIngestBlankNodes() {
        val records = insertFileViaS3("blank-nodes.nt", RDFMediaTypes.N_TRIPLES)
        val knowsRelation = records.find { it.statement.predicate == "http://example.org/knows" }
        val bobNameRelation =
            records.find { it.statement.predicate == "http://example.org/name" && it.statement.`object` == "Bob" }
        assertEquals(knowsRelation?.statement?.`object`, bobNameRelation?.statement?.subject)
        assertTrue(
            bobNameRelation?.statement?.subject?.startsWith(podUri) ?: false
        ) { "Generated named node for blank node ('${bobNameRelation?.statement?.subject}') does not start with '$podUri'" }
        // Delete the file
        deleteFileFromS3("blank-nodes.nt")
    }

    @Test
    fun testCanIngestNonStrictBooleans() {
        val records = insertFileViaS3("non-strict-booleans.nt", RDFMediaTypes.N_TRIPLES)
        assertTrue(records.find { it.statement.predicate == "http://example.org/boolProperty1" }?.statement?.`object`?.let { it as Boolean }
            ?: false)
        assertFalse(records.find { it.statement.predicate == "http://example.org/boolProperty2" }?.statement?.`object`?.let { it as Boolean }
            ?: true)
        // Delete the file
        deleteFileFromS3("non-strict-booleans.nt")
    }

    private fun insertFileViaS3(fileName: String, rdfType: String): List<ChangeRecord> {
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
                result.items.any { report -> changeRequestMatch(report, fileName, ts) }
            })
            .await().indefinitely()
        val changeRequestId = changeHistoryResult.items.find { changeRequestMatch(it, fileName, ts) }!!.id

        // Wait for the change request to be processed
        testHelpers.waitForChangeRequest(changeRequestId, podUri).await()
            .indefinitely()

        // Fetch the inserted records
        return kg.streamChangeRecords(ChangeRecordRequest(podUri, changeRequestId, pageSize = 10000)).collect().asList()
            .await()
            .indefinitely()
    }

    private fun deleteFileFromS3(fileName: String) {
        given()
            .delete("$podUri/s3/$fileName")
            .then().statusCode(204)
    }

    private fun changeRequestMatch(report: ChangeReport, fileName: String, afterTs: Instant): Boolean {
        // Find a report that references the uploaded s3 object and was being processed after the timestamp
        val matchingReport =
            report.statusEntry.any {
                it.timestamp.isAfter(afterTs) && it.message?.contains("Finished processing external references") == true && run {
                    val report = JsonObject(it.message!!.substringAfter("Details: "))
                    report.getJsonArray("insert_refs").map { ref -> ref.toString() }
                        .any { ref -> ref.endsWith(fileName) }
                }
            }
        return matchingReport
    }

}