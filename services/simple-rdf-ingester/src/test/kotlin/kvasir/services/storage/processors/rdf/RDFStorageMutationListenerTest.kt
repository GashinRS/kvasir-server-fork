package kvasir.services.storage.processors.rdf

import com.github.f4b6a3.uuid.UuidCreator
import com.github.f4b6a3.uuid.util.UuidUtil
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import kvasir.definitions.kg.ChangeRecord
import kvasir.definitions.kg.ChangeRecordRequest
import kvasir.definitions.kg.ChangeRecordType
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.definitions.kg.changes.ChangeStatusCode
import kvasir.definitions.kg.changes.ProcessedChange
import kvasir.definitions.kg.changes.S3Reference
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.persistence.Sort
import kvasir.definitions.persistence.SortOrder
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.utils.test.commons.AbstractPodTest
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.Rio
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.fail
import java.time.Instant

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RDFStorageMutationListenerTest : AbstractPodTest() {

    @Inject
    lateinit var repositoryFactory: RepositoryFactory

    @Inject
    lateinit var kg: KnowledgeGraph

    @Test
    fun testMutationListener() {
        val numberOfTriples =
            RDFStorageMutationListenerTest::class.java.getResourceAsStream("/SWAPI-WD-data.ttl").use { inputStream ->
                val model = Rio.parse(inputStream, RDFFormat.TURTLE)
                model.size
            }

        // Fetch the inserted records
        val records = insertFileViaS3("SWAPI-WD-data.ttl", RDFMediaTypes.TURTLE)
        assertEquals(numberOfTriples, records.size)
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
        assertTrue(
            records.find { it.statement.predicate == "http://example.org/boolProperty1" }?.statement?.`object`?.toBoolean()
                ?: false
        )
        assertFalse(
            records.find { it.statement.predicate == "http://example.org/boolProperty2" }?.statement?.`object`?.toBoolean()
                ?: true
        )
        // Delete the file
        deleteFileFromS3("non-strict-booleans.nt")
    }

    private fun insertFileViaS3(fileName: String, rdfType: String): List<ChangeRecord> {
        val ts = UuidUtil.getInstant(UuidCreator.getTimeOrderedEpoch())
        // Upload RDF file to S3
        given()
            .contentType(rdfType)
            .body(RDFStorageMutationListenerTest::class.java.getResourceAsStream("/$fileName"))
            .put("$podUri/s3/$fileName")
            .then().statusCode(200)

        val changeHistoryResult = testHelpers.waitForCondition(
            {
                repositoryFactory.getRepository(ProcessedChange::class, podUri)
                    .find(sort = Sort.by("id", order = SortOrder.DESC))
            },
            { result ->
                result.items.any { report -> changeRequestMatch(report, fileName, ts) }
            })
            .await().indefinitely()
        val changeReport = changeHistoryResult.items.find { changeRequestMatch(it, fileName, ts) }!!
        if (changeReport.getStatusCode() != ChangeStatusCode.COMMITTED) {
            fail("Change request for '$fileName' failed, status: ${changeReport.getStatusCode()}; error: ${changeReport.getErrorMessage()}")
        }

        // Fetch the inserted records
        return kg.streamChangeRecords(ChangeRecordRequest(podUri, changeReport.id, pageSize = 10000)).collect().asList()
            .await()
            .indefinitely()
    }

    private fun deleteFileFromS3(fileName: String) {
        given()
            .delete("$podUri/s3/$fileName")
            .then().statusCode(204)
    }

    private fun changeRequestMatch(report: ProcessedChange, fileName: String, afterTs: Instant): Boolean {
        return report.getLastModifiedAt()!!
            .toEpochMilli() >= afterTs.toEpochMilli() && report.associatedReferences.any { it.changeType == ChangeRecordType.INSERT && it.reference is S3Reference && (it.reference as S3Reference).key == fileName }
    }

}