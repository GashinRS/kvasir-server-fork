package kvasir.services.api.pods

import com.github.jsonldjava.utils.JsonUtils
import io.quarkus.test.common.http.TestHTTPEndpoint
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.get
import io.restassured.RestAssured.given
import jakarta.ws.rs.core.HttpHeaders
import kvasir.definitions.kg.slices.EmbeddedSliceSchema
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.rdf.*
import kvasir.utils.test.commons.AbstractPodTest
import kvasir.utils.test.commons.TestConstants
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@QuarkusTest
@TestHTTPEndpoint(SliceManagementApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SliceManagementApiTest : AbstractPodTest() {

    val sliceName = "test1"
    val filterEmailDomain = "@slice-test.org"
    lateinit var sliceUri: String

    lateinit var nonNamedSliceUri: String

    @Test
    @Order(1)
    @TestSecurity(user = "alice")
    fun testCreateSlice() {
        val sliceDefinition = """
            type Query {
                persons: [ex_Person!]!
                person(id: ID!): ex_Person
            }
            
            type ex_Person {
                id: ID!
                so_givenName: String!
                familyName: String! @predicate(iri: "so:familyName")
                so_email: [String!]! @filter(if: "it==*$filterEmailDomain")
            }
        """.trimIndent()
        val input = SliceInput(
            name = sliceName,
            context = TestConstants.CONTEXT,
            schema = EmbeddedSliceSchema((sliceDefinition))
        )

        sliceUri = given()
            .contentType(RDFMediaTypes.JSON_LD)
            .body(JsonLdHelper.encode(input))
            .post("{podId}/slices", podName)
            .then()
            .statusCode(201)
            .extract().header(HttpHeaders.LOCATION)

        val sliceDef = get(sliceUri).then().statusCode(200).extract().body().asString()
            .let { JsonLdHelper.decode(it, Slice::class.java) }
        assertFalse(sliceDef.supportsChanges)
    }

    @Test
    @Order(2)
    @TestSecurity(user = "alice")
    fun testCreateNonNamedSlice() {
        val sliceDefinition = """
            type Query {
                persons: [ex_Person!]!
                person(id: ID!): ex_Person
            }
            
            type ex_Person {
                id: ID!
                so_givenName: String!
                familyName: String! @predicate(iri: "so:familyName")
                so_email: [String!]! @filter(if: "it==*$filterEmailDomain")
            }
        """.trimIndent()
        val input = SliceInput(
            context = TestConstants.CONTEXT,
            schema = EmbeddedSliceSchema(sliceDefinition)
        )

        nonNamedSliceUri = given()
            .contentType(RDFMediaTypes.JSON_LD)
            .body(JsonLdHelper.encode(input))
            .post("{podId}/slices", podName)
            .then()
            .statusCode(201)
            .extract().header(HttpHeaders.LOCATION)

        val sliceDef = get(sliceUri).then().statusCode(200).extract().body().asString()
            .let { JsonLdHelper.decode(it, Slice::class.java) }
        assertFalse(sliceDef.supportsChanges)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "alice")
    fun testListSlices() {
        val result = JsonUtils.fromString(
            get("{podId}/slices", podName)
                .then()
                .statusCode(200)
                .extract().body().asString()
        ) as JSONObject
        assertEquals(setOf(sliceUri, nonNamedSliceUri), result.getJsonArray<JSONObject>(JsonLdKeywords.graph)?.map {
            it.get(
                JsonLdKeywords.id
            )
        }?.toSet())
    }

    @Test
    @Order(5)
    @TestSecurity(user = "alice")
    fun testUpdateSliceToAddMutations() {
        val sliceDefinition = """
            type Query {
                persons: [ex_Person!]!
                person(id: ID!): ex_Person
            }
            
            type Mutation {
                insertPerson(input: PersonInput): ID!
                deletePerson(input: PersonInput): ID!
            }
            
            type ex_Person {
                id: ID!
                so_givenName: String!
                familyName: String! @predicate(iri: "so:familyName")
                so_email: [String!]! @filter(if: "it==*$filterEmailDomain")
            }
            
            input PersonInput @class(iri: "ex:Person") {
                id: ID!
                so_givenName: String!
                so_familyName: String!
                so_email: [String!] @shape(pattern: "$filterEmailDomain")
            }
        """.trimIndent()
        val input = SliceInput(
            name = sliceName,
            context = TestConstants.CONTEXT,
            schema = EmbeddedSliceSchema(sliceDefinition)
        )

        given()
            .contentType(RDFMediaTypes.JSON_LD)
            .body(JsonLdHelper.encode(input))
            .put(sliceUri)
            .then().statusCode(204)

        // Fetch the Slice definition, mutations should now be enabled.
        val sliceDef = get(sliceUri).then().statusCode(200).extract().body().asString()
            .let { JsonLdHelper.decode(it, Slice::class.java) }
        assertTrue(sliceDef.supportsChanges)
    }

    @Test
    @Order(6)
    @TestSecurity(user = "alice")
    fun testListSlicesIncludesLineage() {
        // ── Create a new slice tagged "stable" ────────────────────────────────
        val lineageSliceName = "lineage-test"
        val schema = """
            type Query { persons: [ex_Person!]! }
            type ex_Person { id: ID! so_givenName: String! }
        """.trimIndent()

        val lineageSliceUri = given()
            .contentType(RDFMediaTypes.JSON_LD)
            .body(
                JsonLdHelper.encode(
                    SliceInput(
                        name = lineageSliceName,
                        context = TestConstants.CONTEXT,
                        schema = EmbeddedSliceSchema(schema),
                        tags = setOf("stable")
                    )
                )
            )
            .post("{podId}/slices", podName)
            .then().statusCode(201)
            .extract().header(HttpHeaders.LOCATION)

        // ── Immediately after tagging: the head IS the tagged revision (0 ahead)
        run {
            @Suppress("UNCHECKED_CAST")
            val graph0 = (JsonUtils.fromString(
                given().get("{podId}/slices", podName).then().statusCode(200).extract().body().asString()
            ) as JSONObject).getJsonArray<JSONObject>(JsonLdKeywords.graph) ?: emptyList()

            val entry0 = graph0.firstOrNull { it[JsonLdKeywords.id] == lineageSliceUri }
            assertNotNull(entry0, "Lineage slice must appear in the list right after creation")

            @Suppress("UNCHECKED_CAST")
            val lineage0 = entry0!!["kss:lineage"] as? JSONObject
            assertNotNull(lineage0, "A tagged revision should already have lineage info (0 ahead)")

            val nAhead0 = lineage0!!["kss:numberOfRevisionsAhead"]
            assertEquals(
                0, (nAhead0 as? Number)?.toInt() ?: nAhead0,
                "A revision that IS the tagged one should be 0 revisions ahead"
            )

            @Suppress("UNCHECKED_CAST")
            val tags0 = (lineage0["kss:tags"] as? List<*>)?.map { it.toString() }
                ?: listOf(lineage0["kss:tags"].toString())
            assertTrue(tags0.contains("stable"), "The 'stable' tag should appear in lineage even at 0 ahead")
        }

        // ── Push one more revision without a tag — now 1 revision ahead of "stable"
        given()
            .contentType(RDFMediaTypes.JSON_LD)
            .body(
                JsonLdHelper.encode(
                    SliceInput(
                        name = lineageSliceName,
                        context = TestConstants.CONTEXT,
                        schema = EmbeddedSliceSchema(schema),
                        description = "revision 2 – untagged"
                    )
                )
            )
            .put(lineageSliceUri)
            .then().statusCode(204)

        // ── GET slice list and verify lineage ─────────────────────────────────
        val bodyStr = given().get("{podId}/slices", podName)
            .then().statusCode(200)
            .extract().body().asString()
        @Suppress("UNCHECKED_CAST")
        val graph =
            (JsonUtils.fromString(bodyStr) as JSONObject).getJsonArray<JSONObject>(JsonLdKeywords.graph) ?: emptyList()

        val lineageEntry = graph.firstOrNull { it[JsonLdKeywords.id] == lineageSliceUri }
        assertNotNull(lineageEntry, "Lineage slice must appear in the slice list")

        @Suppress("UNCHECKED_CAST")
        val lineage = lineageEntry!!["kss:lineage"] as? JSONObject
        assertNotNull(lineage, "The lineage slice should have lineage info after an untagged update")

        val nAhead = lineage!!["kss:numberOfRevisionsAhead"]
        assertEquals(
            1, (nAhead as? Number)?.toInt() ?: nAhead,
            "The slice should be 1 revision ahead of the 'stable' tag"
        )

        @Suppress("UNCHECKED_CAST")
        val tags = (lineage["kss:tags"] as? List<*>)?.map { it.toString() }
            ?: listOf(lineage["kss:tags"].toString())
        assertTrue(tags.contains("stable"), "Lineage tags should include 'stable'")

        // ── Slices without any tag should have no lineage ─────��──────────────
        val untaggedEntry = graph.firstOrNull { it[JsonLdKeywords.id] == sliceUri }
        assertNotNull(untaggedEntry, "The original slice must appear in the list")
        assertNull(
            untaggedEntry!!["kss:lineage"],
            "A slice that was never tagged should have null lineage"
        )
    }

}
