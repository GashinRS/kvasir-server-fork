package kvasir.services.api.kg.query

import com.github.jsonldjava.utils.JsonUtils
import io.quarkus.test.common.http.TestHTTPEndpoint
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.kg.ChangeFinalizeRequest
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.definitions.kg.changes.ChangeRequest
import kvasir.definitions.kg.graphql.FIELD_ID_NAME
import kvasir.definitions.kg.slices.EmbeddedSliceSchema
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirNamedGraphs
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.definitions.rdf.XSDVocab
import kvasir.utils.idgen.ChangeRequestId
import kvasir.utils.idgen.StateId
import kvasir.utils.test.commons.AbstractPodTest
import kvasir.utils.test.commons.getDataField
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant

/**
 * Integration tests for schema-aware scalar mapping in [kvasir.definitions.kg.QueryResult.toJsonLD].
 *
 * Verifies that when querying a Slice as JSON-LD the following type-specific conversions are applied:
 * - Fields declared as `ID` in the GraphQL schema become `{"@id": "..."}` IRI nodes instead of plain strings.
 * - Fields declared as `DateTime` become `{"@type": "xsd:dateTime", "@value": "..."}` typed-value objects.
 * - Plain JSON queries (no `Accept: application/ld+json`) are unaffected and continue to return raw values.
 */
@QuarkusTest
@TestHTTPEndpoint(GraphSlicesApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SliceJsonLdScalarMappingTest : AbstractPodTest() {

    @Inject
    lateinit var kg: KnowledgeGraph

    @Inject
    lateinit var repositoryFactory: RepositoryFactory

    private val sliceName = "scalar-mapping-test"
    private lateinit var sliceUri: String

    // Test data — deterministic IRIs so assertions are exact.
    private val appointmentId = "http://example.org/appointment-1"
    private val attendeeId1 = "http://example.org/person-1"
    private val attendeeId2 = "http://example.org/person-2"
    private val scheduledAt = "2024-01-15T10:30:00Z"

    /**
     * JSON-LD context for the slice.
     * `xsd` is included so that DateTime type IRIs compact to readable `xsd:dateTime` in assertions.
     */
    private val testContext = mapOf(
        "ex" to "http://example.org/",
        "xsd" to XSDVocab.baseUri
    )

    /**
     * Slice schema.
     *
     * - `ex_Appointment` is the output type (maps to `http://example.org/Appointment`).
     * - `ex_attendee` is typed as `[ID!]!` — an IRI reference that GraphQL returns as raw strings.
     * - `ex_scheduledAt` is typed as `DateTime!` — a temporal literal.
     */
    private val sliceSchema = """
        type Query {
            ex_Appointment: [ex_Appointment!]!
        }

        type ex_Appointment {
            id: ID!
            ex_scheduledAt: DateTime!
            ex_attendee: [ID!]!
        }
    """.trimIndent()

    @BeforeAll
    fun setupSliceAndData() {
        sliceUri = "$podUri/slices/$sliceName"

        // Persist the Slice definition directly (bypasses HTTP, no security context needed).
        repositoryFactory.getVersionedRepository(Slice::class, podUri).persist(
            Slice(
                id = sliceUri,
                name = sliceName,
                description = "Test slice for JSON-LD scalar type mapping",
                createdBy = "alice",
                context = testContext,
                schema = EmbeddedSliceSchema(sliceSchema)
            )
        ).await().indefinitely()

        // Insert one appointment with two attendees (IRI references) and a DateTime literal.
        kg.process(
            ChangeRequest(
                id = ChangeRequestId.generate("$podUri/changes").encode(),
                changeId = StateId.generate(),
                context = emptyMap(),
                requestingUser = "alice",
                podId = podUri,
                insert = listOf(
                    mapOf(
                        JsonLdKeywords.id to appointmentId,
                        JsonLdKeywords.type to "http://example.org/Appointment",
                        // Store a DateTime literal using the JSON-LD typed-value notation.
                        "http://example.org/scheduledAt" to mapOf(
                            JsonLdKeywords.type to XSDVocab.dateTime,
                            JsonLdKeywords.value to scheduledAt
                        ),
                        // Store two IRI references as attendees.
                        "http://example.org/attendee" to listOf(
                            mapOf(JsonLdKeywords.id to attendeeId1),
                            mapOf(JsonLdKeywords.id to attendeeId2)
                        )
                    )
                )
            )
        ).chain { processedChange ->
            kg.finalize(ChangeFinalizeRequest(podUri, processedChange.id))
        }.await().indefinitely()
    }

    // ── JSON-LD output tests ──────────────────────────────────────────────────

    /**
     * Querying the slice with `Accept: application/ld+json` must produce IRI node objects
     * (`{"@id": "..."}`) for fields declared as `ID` in the schema — not plain string literals.
     *
     * Without schema-aware mapping the values would be `"http://example.org/person-1"` (a literal),
     * which is semantically incorrect RDF; with the fix they become `{"@id": "ex:person-1"}`.
     */
    @Test
    @TestSecurity(user = "alice")
    fun testIdFieldsBecomesIriNodesInJsonLd() {
        val rawResult = given()
            .accept(RDFMediaTypes.JSON_LD)
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl("{ ex_Appointment { id ex_attendee } }"))
            .post("{podId}/slices/{sliceId}/query", podName, sliceName)
            .then()
            .statusCode(200)
            .extract().body().asString()

        val response = JsonUtils.fromString(rawResult) as List<Map<String, Any>>
        val appointment = extractAppointment(response)

        // ex_attendee must be a list of IRI-node objects, each having an "@id" key.
        // A plain string value here would indicate the fix is not working.
        @Suppress("UNCHECKED_CAST")
        val attendees = appointment["ex:attendee"] as? List<*>
        assertNotNull(attendees, "ex:attendee should be present in the JSON-LD result")
        assertEquals(2, attendees!!.size, "Both attendees should appear")
        assertTrue(
            attendees.all { it is Map<*, *> && it.containsKey(JsonLdKeywords.id) },
            "Every attendee entry must be an IRI-node map {\"@id\": ...}, not a plain string. " +
                    "Actual: $attendees"
        )

        // Also verify the actual IRI values are present (compacted to ex:person-X by the context).
        val attendeeIds = attendees.map { (it as Map<*, *>)[JsonLdKeywords.id].toString() }.toSet()
        assertTrue(attendeeIds.any { it.contains("person-1") }, "person-1 should be present: $attendeeIds")
        assertTrue(attendeeIds.any { it.contains("person-2") }, "person-2 should be present: $attendeeIds")
    }

    /**
     * Querying the slice with `Accept: application/ld+json` must produce a typed-value object
     * (`{"@type": "xsd:dateTime", "@value": "..."}`) for fields declared as `DateTime!` in the schema,
     * not a plain string.
     */
    @Test
    @TestSecurity(user = "alice")
    fun testDateTimeFieldBecomesTypedValueInJsonLd() {
        val rawResult = given()
            .accept(RDFMediaTypes.JSON_LD)
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl("{ ex_Appointment { id ex_scheduledAt } }"))
            .post("{podId}/slices/{sliceId}/query", podName, sliceName)
            .then()
            .statusCode(200)
            .extract().body().asString()

        val response = JsonUtils.fromString(rawResult) as List<Map<String, Any>>
        val appointment = extractAppointment(response)

        // ex_scheduledAt must be a typed literal: {"@type": "xsd:dateTime", "@value": "..."}.
        // A plain string value here would indicate the fix is not working.
        val scheduledAtField = appointment["ex:scheduledAt"]
        assertNotNull(scheduledAtField, "ex:scheduledAt should be present in the JSON-LD result")
        assertTrue(
            scheduledAtField is Map<*, *>,
            "ex:scheduledAt must be a typed-value map {\"@type\": ..., \"@value\": ...}, not a plain string '$scheduledAtField'"
        )

        @Suppress("UNCHECKED_CAST")
        val typedValue = scheduledAtField as Map<String, Any>
        val typeValue = typedValue[JsonLdKeywords.type]?.toString() ?: ""
        // @type is compacted by the xsd prefix in the context → "xsd:dateTime"
        assertTrue(typeValue.contains("dateTime"), "Expected @type to contain 'dateTime', got: $typeValue")
        assertEquals(
            Instant.parse(scheduledAt), typedValue[JsonLdKeywords.value]?.toString()?.let { Instant.parse(it) },
            "The @value should match the stored timestamp"
        )
    }

    // ── Plain-JSON regression test ────────────────────────────────────────────

    /**
     * A plain `application/json` query (no JSON-LD Accept header) must still return raw values
     * unchanged — the schema-aware conversion only applies to the JSON-LD transformation path.
     */
    @Test
    @TestSecurity(user = "alice")
    fun testPlainJsonQueryIsUnaffected() {
        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl("{ ex_Appointment { id ex_attendee ex_scheduledAt } }"))
            .post("{podId}/slices/{sliceId}/query", podName, sliceName)
            .then()
            .statusCode(200)
            .extract().body().`as`(kvasir.definitions.kg.QueryResult::class.java)

        val appointments = result.getDataField<List<Map<String, Any>>>("ex_Appointment")
        assertNotNull(appointments, "ex_Appointment should be present")
        val appointment = appointments!!.first { it[FIELD_ID_NAME] == appointmentId }

        // In plain JSON mode, attendee values are raw strings (not wrapped in @id maps).
        @Suppress("UNCHECKED_CAST")
        val attendees = appointment["ex_attendee"] as? List<*>
        assertNotNull(attendees, "ex_attendee should be present")
        assertTrue(
            attendees!!.all { it is String },
            "In plain JSON mode, attendee values should be raw strings, got: $attendees"
        )
        assertEquals(
            setOf(attendeeId1, attendeeId2),
            attendees.map { it.toString() }.toSet()
        )

        // In plain JSON mode, scheduledAt value is a raw string.
        val scheduledAtField = appointment["ex_scheduledAt"]
        assertTrue(
            scheduledAtField is String,
            "In plain JSON mode, ex_scheduledAt should be a plain string, got: ${scheduledAtField?.javaClass}"
        )
        assertTrue(
            scheduledAtField.toString().contains("2024-01-15"),
            "Scheduled-at string should contain the date, got: $scheduledAtField"
        )
    }

    // ── Navigation helper ─────────────────────────────────────────────────────

    /**
     * Navigates the raw JSON-LD response list to extract the appointment map.
     *
     * Response structure (post `toJsonLD` compaction):
     * ```
     * [
     *   {
     *     "@id":      "kvasir-named-graphs#qr-data",
     *     "@context": { ... },
     *     "@graph":   [ { "ex:Appointment": [ { "@id": "...", ... }, ... ] } ]
     *   },
     *   ...
     * ]
     * ```
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractAppointment(response: List<Map<String, Any>>): Map<String, Any> {
        val dataEntry = response.find { it[JsonLdKeywords.id] == KvasirNamedGraphs.queryResultDataGraph }
            ?: fail("Data graph entry (${KvasirNamedGraphs.queryResultDataGraph}) not found in: $response")

        val graph = dataEntry[JsonLdKeywords.graph] as List<Map<String, Any>>
        val appointments =
            graph.first()["ex:Appointment"]?.let { if (it is Iterable<*>) it as? List<Map<String, Any>> else listOf(it as Map<String, Any>) }
                ?: fail("\"ex:Appointment\" key not found in graph root. Available keys: ${graph.first().keys}")

        return appointments.firstOrNull { it[JsonLdKeywords.id]?.toString()?.contains("appointment") == true }
            ?: appointments.firstOrNull()
            ?: fail("No appointment found in result: $appointments")
    }
}






