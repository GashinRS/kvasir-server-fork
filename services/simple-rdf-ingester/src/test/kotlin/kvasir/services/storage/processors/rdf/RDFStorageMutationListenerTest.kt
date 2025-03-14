package kvasir.services.storage.processors.rdf

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import kvasir.definitions.kg.ChangeRecordRequest
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.definitions.kg.changes.ChangeHistory
import kvasir.definitions.kg.changes.ChangeHistoryRequest
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.utils.test.clickhouse.ClickhouseTestResource
import kvasir.utils.test.commons.TestHelpers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

private const val TEST_FILE_NUMBER_OF_STATEMENTS = 68976

@QuarkusTest
@QuarkusTestResource(ClickhouseTestResource::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RDFStorageMutationListenerTest {

    @Inject
    lateinit var testHelpers: TestHelpers

    @Inject
    lateinit var changeHistory: ChangeHistory

    @Inject
    lateinit var kg: KnowledgeGraph

    @Test
    fun testMutationListener() {
        val podUri = testHelpers.getPodUri()
        // Upload RDF file to S3
        given()
            .contentType(RDFMediaTypes.TURTLE)
            .body(RDFStorageMutationListenerTest::class.java.getResourceAsStream("/SWAPI-WD-data.ttl"))
            .put("$podUri/s3/SWAPI-WD-data.ttl")
            .then().statusCode(200)

        val changeHistoryResult = testHelpers.waitForCondition(
            { changeHistory.list(ChangeHistoryRequest(podUri)) },
            { it.items.isNotEmpty() }).await().indefinitely()
        val changeRequestId = changeHistoryResult.items.first().id
        testHelpers.waitForChangeRequest(changeRequestId, podUri).await()
            .indefinitely()

        // Fetch the inserted records
        val records =
            kg.streamChangeRecords(ChangeRecordRequest(podUri, changeRequestId, pageSize = 10000)).collect().asList()
                .await()
                .indefinitely()
        assertEquals(TEST_FILE_NUMBER_OF_STATEMENTS, records.size)
    }

}