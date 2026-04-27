package kvasir.services.api.kg.changes

import com.github.jsonldjava.utils.JsonUtils
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import kvasir.definitions.kg.changes.ChangeStatusCode
import kvasir.definitions.kg.changes.ProcessedChange
import kvasir.definitions.kg.slices.EmbeddedSliceSchema
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.utils.test.commons.AbstractPodTest
import kvasir.utils.test.commons.SchemaVocab
import kvasir.utils.test.commons.TestConstants
import kvasir.utils.test.commons.TestDataGenerator
import org.junit.jupiter.api.*
import java.util.*

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SliceChangesApiTest : AbstractPodTest() {

    @Inject
    lateinit var repositoryFactory: RepositoryFactory

    private val sliceName = "tagged-changes-test"
    private val tagName = "v1"
    private lateinit var sliceUri: String
    private lateinit var committedChangeId: String

    private val sliceSchema = """
        type Mutation {
            insert(input: PersonInput!): ID!
            delete(input: PersonInput!): ID!
        }

        input PersonInput @class(iri: "ex:Person") {
            id: ID!
            so_givenName: String!
            so_familyName: String! @shape(minLength: 2)
            so_email: [String!]
        }
    """.trimIndent()

    private fun decodeChangeGraph(body: String): List<ProcessedChange> {
        @Suppress("UNCHECKED_CAST")
        val root = JsonUtils.fromString(body) as JSONObject
        @Suppress("UNCHECKED_CAST")
        val context = root[JsonLdKeywords.context]
        @Suppress("UNCHECKED_CAST")
        val graph = (root[JsonLdKeywords.graph] as? List<*>) ?: return emptyList()
        return graph.mapNotNull { item ->
            @Suppress("UNCHECKED_CAST")
            val entry = (item as? Map<String, Any>) ?: return@mapNotNull null
            val withContext = if (context != null) mapOf(JsonLdKeywords.context to context) + entry else entry
            JsonLdHelper.decode(JsonUtils.toString(withContext), ProcessedChange::class.java)
        }
    }

    /**
     * Creates the slice with mutation support and tags it as "v1".
     * Must run before all change-related tests.
     */
    @Test
    @Order(1)
    @TestSecurity(user = "alice")
    fun setupTaggedSlice() {
        sliceUri = "$podUri/slices/$sliceName"
        val slice = Slice(
            id = sliceUri,
            name = sliceName,
            description = "",
            createdBy = "alice",
            context = TestConstants.CONTEXT,
            schema = EmbeddedSliceSchema(sliceSchema),
            supportsChanges = true
        )
        repositoryFactory.getVersionedRepository(Slice::class, podUri).persist(slice, setOf(tagName)).await().indefinitely()
    }

    @Test
    @Order(2)
    @TestSecurity(user = "alice")
    fun testPostValidChangeToTaggedSlice() {
        val taggedSliceUri = "$sliceUri/tags/$tagName"
        val validPersonData = TestDataGenerator.generatePersonData(1)
        val body = JsonLdHelper.encode(ChangeRequestInput(context = TestConstants.CONTEXT, insert = validPersonData))
        committedChangeId = testHelpers.requestChangeViaHTTPSync(body, podUri, sliceUri = taggedSliceUri)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "alice")
    fun testPostInvalidChangeToTaggedSliceIsRejected() {
        val taggedSliceUri = "$sliceUri/tags/$tagName"
        // familyName "D" is too short – @shape(minLength: 2) should trigger VALIDATION_ERROR
        val invalidPersonData = listOf(
            mapOf(
                JsonLdKeywords.id to "ex:${UUID.randomUUID()}",
                JsonLdKeywords.type to "ex:Person",
                SchemaVocab.givenName to "Joe",
                SchemaVocab.familyName to "D"
            )
        )
        val body = JsonLdHelper.encode(ChangeRequestInput(context = TestConstants.CONTEXT, insert = invalidPersonData))
        testHelpers.requestChangeViaHTTPSync(
            body,
            podUri,
            ChangeStatusCode.VALIDATION_ERROR,
            sliceUri = taggedSliceUri
        )
    }

    @Test
    @Order(4)
    @TestSecurity(user = "alice")
    fun testPostChangeToNonExistentTagReturns404() {
        val body = JsonLdHelper.encode(
            ChangeRequestInput(
                context = TestConstants.CONTEXT,
                insert = TestDataGenerator.generatePersonData(1)
            )
        )
        given()
            .contentType(RDFMediaTypes.JSON_LD)
            .body(body)
            .post("$sliceUri/tags/nonexistent-tag/changes")
            .then()
            .statusCode(404)
    }

    @Test
    @Order(5)
    @TestSecurity(user = "alice")
    fun testPostChangeToNonExistentSliceReturns404() {
        val body = JsonLdHelper.encode(
            ChangeRequestInput(
                context = TestConstants.CONTEXT,
                insert = TestDataGenerator.generatePersonData(1)
            )
        )
        given()
            .contentType(RDFMediaTypes.JSON_LD)
            .body(body)
            .post("$podUri/slices/nonexistent-slice/tags/$tagName/changes")
            .then()
            .statusCode(404)
    }

    @Test
    @Order(7)
    @TestSecurity(user = "alice")
    fun testListChangeReportsForNonExistentTagReturns404() {
        given()
            .accept(RDFMediaTypes.JSON_LD)
            .get("$sliceUri/tags/nonexistent-tag/changes")
            .then()
            .statusCode(404)
    }

    @Test
    @Order(8)
    @TestSecurity(user = "alice")
    fun testGetChangeReportAtTaggedEndpoint() {
        val report = JsonLdHelper.decode(
            given()
                .accept(RDFMediaTypes.JSON_LD)
                .get("$sliceUri/tags/$tagName/changes/$committedChangeId")
                .then()
                .statusCode(200)
                .extract().body().asString(),
            ProcessedChange::class.java
        )
        Assertions.assertEquals(committedChangeId, report.id.substringAfterLast('/'),
            "Returned change ID must match the requested one")
        Assertions.assertEquals(ChangeStatusCode.COMMITTED, report.getStatusCode())
        Assertions.assertEquals(sliceUri, report.sliceId)
        Assertions.assertTrue(report.nrOfInserts > 0)
    }

    @Test
    @Order(9)
    @TestSecurity(user = "alice")
    fun testGetChangeRecordsAtTaggedEndpoint() {
        val body = given()
            .accept(RDFMediaTypes.JSON_LD)
            .get("$sliceUri/tags/$tagName/changes/$committedChangeId/records")
            .then()
            .statusCode(200)
            .extract().body().asString()

        // The records response must contain at least one inserted entity
        val parsed = JsonLdHelper.mapper.readTree(body)
        val inserts = parsed["kss:insert"] ?: parsed["https://kvasir.discover.ilabt.imec.be/vocab#insert"]
        Assertions.assertNotNull(inserts, "Records response must contain kss:insert")
        Assertions.assertFalse(inserts.isEmpty, "At least one insert record must be present")
    }

    // ── Non-tagged /changes endpoints ────────────────────────────────────────

    @Test
    @Order(10)
    @TestSecurity(user = "alice")
    fun testListChangeReportsAtSliceEndpoint() {
        // Slice-level (non-tagged) endpoint should also list the committed change
        val body = given()
            .accept(RDFMediaTypes.JSON_LD)
            .get("$sliceUri/changes")
            .then()
            .statusCode(200)
            .extract().body().asString()

        val reports = decodeChangeGraph(body)
        Assertions.assertFalse(reports.isEmpty(), "Non-tagged /changes list must not be empty")
        val committed = reports.filter { it.getStatusCode() == ChangeStatusCode.COMMITTED }
        Assertions.assertFalse(committed.isEmpty(), "At least one COMMITTED change must appear")
        Assertions.assertTrue(
            committed.any { it.id.substringAfterLast('/') == committedChangeId },
            "The committed change from @Order(2) must appear in the non-tagged slice /changes list"
        )
    }

    @Test
    @Order(11)
    @TestSecurity(user = "alice")
    fun testGetChangeReportAtSliceEndpoint() {
        val report = JsonLdHelper.decode(
            given()
                .accept(RDFMediaTypes.JSON_LD)
                .get("$sliceUri/changes/$committedChangeId")
                .then()
                .statusCode(200)
                .extract().body().asString(),
            ProcessedChange::class.java
        )
        Assertions.assertEquals(committedChangeId, report.id.substringAfterLast('/'))
        Assertions.assertEquals(ChangeStatusCode.COMMITTED, report.getStatusCode())
        Assertions.assertEquals(sliceUri, report.sliceId)
    }

    @Test
    @Order(12)
    @TestSecurity(user = "alice")
    fun testGetChangeRecordsAtSliceEndpoint() {
        val body = given()
            .accept(RDFMediaTypes.JSON_LD)
            .get("$sliceUri/changes/$committedChangeId/records")
            .then()
            .statusCode(200)
            .extract().body().asString()

        val parsed = JsonLdHelper.mapper.readTree(body)
        val inserts = parsed["kss:insert"] ?: parsed["https://kvasir.discover.ilabt.imec.be/vocab#insert"]
        Assertions.assertNotNull(inserts, "Records response must contain kss:insert")
        Assertions.assertFalse(inserts.isEmpty, "At least one insert record must be present")
    }

}
