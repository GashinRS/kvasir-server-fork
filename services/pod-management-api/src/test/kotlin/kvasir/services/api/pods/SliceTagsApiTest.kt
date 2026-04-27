package kvasir.services.api.pods

import com.github.jsonldjava.utils.JsonUtils
import io.quarkus.test.common.http.TestHTTPEndpoint
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.ws.rs.core.HttpHeaders
import kvasir.definitions.kg.slices.EmbeddedSliceSchema
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.utils.test.commons.AbstractPodTest
import kvasir.utils.test.commons.TestConstants
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@QuarkusTest
@TestHTTPEndpoint(SliceManagementApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SliceTagsApiTest : AbstractPodTest() {

    val sliceName = "tagged-slice"
    val tagV1 = "v1"
    val tagV2 = "v2"
    val tagV3 = "v3"
    lateinit var sliceUri: String

    val schemaV1 = """
        type Query {
            persons: [ex_Person!]!
        }
        type ex_Person {
            id: ID!
            so_givenName: String!
        }
    """.trimIndent()

    val schemaV2 = """
        type Query {
            persons: [ex_Person!]!
            person(id: ID!): ex_Person
        }
        type ex_Person {
            id: ID!
            so_givenName: String!
            so_familyName: String!
        }
    """.trimIndent()

    val schemaV3 = """
        type Query {
            persons: [ex_Person!]!
            person(id: ID!): ex_Person
        }
        type ex_Person {
            id: ID!
            so_givenName: String!
            so_familyName: String!
            so_email: String
        }
    """.trimIndent()

    /** Schema used to patch the v2 tag branch — adds so_telephone instead of so_email. */
    val schemaV2Patch = """
        type Query {
            persons: [ex_Person!]!
            person(id: ID!): ex_Person
        }
        type ex_Person {
            id: ID!
            so_givenName: String!
            so_familyName: String!
            so_telephone: String
        }
    """.trimIndent()

    @Test
    @Order(1)
    @TestSecurity(user = "alice")
    fun setupTaggedSlice() {
        // POST v1 with tag "v1" via HTTP API
        val inputV1 = SliceInput(
            name = sliceName,
            description = "Version 1",
            context = TestConstants.CONTEXT,
            schema = EmbeddedSliceSchema(schemaV1),
            tags = setOf(tagV1)
        )
        sliceUri = given()
            .contentType(RDFMediaTypes.JSON_LD)
            .body(JsonLdHelper.encode(inputV1))
            .post("{podId}/slices", podName)
            .then()
            .statusCode(201)
            .extract().header(HttpHeaders.LOCATION)

        // PUT v2 with tag "v2" via HTTP API
        val inputV2 = SliceInput(
            name = sliceName,
            description = "Version 2",
            context = TestConstants.CONTEXT,
            schema = EmbeddedSliceSchema(schemaV2),
            tags = setOf(tagV2)
        )
        given()
            .contentType(RDFMediaTypes.JSON_LD)
            .body(JsonLdHelper.encode(inputV2))
            .put(sliceUri)
            .then()
            .statusCode(204)
    }

    @Test
    @Order(2)
    @TestSecurity(user = "alice")
    fun testListSliceTags() {
        val body = given()
            .accept(RDFMediaTypes.JSON_LD)
            .get("{podId}/slices/{sliceId}/tags", podName, sliceName)
            .then()
            .statusCode(200)
            .extract().body().asString()

        @Suppress("UNCHECKED_CAST")
        val graph = (JsonUtils.fromString(body) as Map<String, Any>)[JsonLdKeywords.graph] as List<Map<String, Any>>

        // Both versions should be returned (DESC order by createdAt, so v2 comes first)
        assertEquals(2, graph.size)
        assertEquals(tagV2, tagOf(graph[0]))
        assertEquals(tagV1, tagOf(graph[1]))
    }

    @Test
    @Order(3)
    @TestSecurity(user = "alice")
    fun testGetSliceAtTagJsonLD() {
        val slice = given()
            .accept(RDFMediaTypes.JSON_LD)
            .get("{podId}/slices/{sliceId}/tags/{tag}", podName, sliceName, tagV1)
            .then()
            .statusCode(200)
            .extract().body().asString()
            .let { JsonLdHelper.decode(it, Slice::class.java) }

        // v1 has description "Version 1" and schema without so_familyName
        assertEquals("Version 1", slice.description)
        val sdl = (slice.schema as EmbeddedSliceSchema).sdl
        assertTrue(sdl.contains("so_givenName"))
        assertFalse(sdl.contains("so_familyName"))
    }

    @Test
    @Order(5)
    @TestSecurity(user = "alice")
    fun testGetSliceAtNonExistentTagReturns404() {
        given()
            .accept(RDFMediaTypes.JSON_LD)
            .get("{podId}/slices/{sliceId}/tags/{tag}", podName, sliceName, "nonexistent-tag")
            .then()
            .statusCode(404)
    }

    @Test
    @Order(6)
    @TestSecurity(user = "alice")
    fun testPutWithTagCreatesNewTaggedVersion() {
        // PUT v3 with tag "v3" via HTTP API
        val inputV3 = SliceInput(
            name = sliceName,
            description = "Version 3",
            context = TestConstants.CONTEXT,
            schema = EmbeddedSliceSchema(schemaV3),
            tags = setOf(tagV3)
        )
        given()
            .contentType(RDFMediaTypes.JSON_LD)
            .body(JsonLdHelper.encode(inputV3))
            .put(sliceUri)
            .then()
            .statusCode(204)

        // v3 tag should resolve to the new version with so_email field
        val sdl = sdlAtTag(tagV3)

        assertTrue(sdl.contains("so_email"))
        assertTrue(sdl.contains("so_familyName"))

        // Earlier tags should still resolve correctly
        val sdlV1 = sdlAtTag(tagV1)
        assertFalse(sdlV1.contains("so_familyName"))
    }

    @Test
    @Order(7)
    @TestSecurity(user = "alice")
    fun testDeleteSliceTag() {
        given()
            .delete("{podId}/slices/{sliceId}/tags/{tag}", podName, sliceName, tagV1)
            .then()
            .statusCode(204)

        // The v1 tag should no longer resolve to a version
        given()
            .accept(RDFMediaTypes.JSON_LD)
            .get("{podId}/slices/{sliceId}/tags/{tag}", podName, sliceName, tagV1)
            .then()
            .statusCode(404)
    }

    @Test
    @Order(8)
    @TestSecurity(user = "alice")
    fun testListSliceTagsAfterDeletion() {
        val body = given()
            .accept(RDFMediaTypes.JSON_LD)
            .get("{podId}/slices/{sliceId}/tags", podName, sliceName)
            .then()
            .statusCode(200)
            .extract().body().asString()

        @Suppress("UNCHECKED_CAST")
        val graph = (JsonUtils.fromString(body) as Map<String, Any>)[JsonLdKeywords.graph] as List<Map<String, Any>>

        // v1 tag is removed; v2 and v3 tags still exist
        assertFalse(graph.any { tagOf(it) == tagV1 })
        assertTrue(graph.any { tagOf(it) == tagV2 })
        assertTrue(graph.any { tagOf(it) == tagV3 })
    }

    @Test
    @Order(9)
    @TestSecurity(user = "alice")
    fun testListSliceTagsForNonExistentSliceReturns404() {
        given()
            .accept(RDFMediaTypes.JSON_LD)
            .get("{podId}/slices/{sliceId}/tags", podName, "nonexistent-slice")
            .then()
            .statusCode(404)
    }

    @Test
    @Order(10)
    @TestSecurity(user = "alice")
    fun testUpdateTaggedRevision() {
        // Capture the main branch head before the patch
        val mainBefore = given()
            .accept(RDFMediaTypes.JSON_LD)
            .get(sliceUri)
            .then()
            .statusCode(200)
            .extract().body().asString()
            .let { JsonLdHelper.decode(it, Slice::class.java) }

        // PUT a new version onto the v2 tag's side branch (no body tags → defaults to overriding v2)
        val patchInput = SliceInput(
            name = sliceName,
            description = "Version 2 patched",
            context = TestConstants.CONTEXT,
            schema = EmbeddedSliceSchema(schemaV2Patch)
        )
        given()
            .contentType(RDFMediaTypes.JSON_LD)
            .body(JsonLdHelper.encode(patchInput))
            .put("{podId}/slices/{sliceId}/tags/{tag}", podName, sliceName, tagV2)
            .then()
            .statusCode(204)

        // v2 tag now points to the patched revision
        val sliceAtV2 = given()
            .accept(RDFMediaTypes.JSON_LD)
            .get("{podId}/slices/{sliceId}/tags/{tag}", podName, sliceName, tagV2)
            .then()
            .statusCode(200)
            .extract().body().asString()
            .let { JsonLdHelper.decode(it, Slice::class.java) }

        assertEquals("Version 2 patched", sliceAtV2.description)
        assertTrue((sliceAtV2.schema as EmbeddedSliceSchema).sdl.contains("so_telephone"))
        assertFalse((sliceAtV2.schema as EmbeddedSliceSchema).sdl.contains("so_email"))

        // Main branch head is completely unaffected — still the v3 revision
        val mainAfter = given()
            .accept(RDFMediaTypes.JSON_LD)
            .get(sliceUri)
            .then()
            .statusCode(200)
            .extract().body().asString()
            .let { JsonLdHelper.decode(it, Slice::class.java) }

        assertEquals(mainBefore.description, mainAfter.description)
        assertEquals(mainBefore.revisionId, mainAfter.revisionId)
        assertTrue((mainAfter.schema as EmbeddedSliceSchema).sdl.contains("so_email"))
        assertFalse((mainAfter.schema as EmbeddedSliceSchema).sdl.contains("so_telephone"))
    }

    @Test
    @Order(11)
    @TestSecurity(user = "alice")
    fun testAliasTag() {
        val tagAlias = "v2-stable"

        // Alias v2 as v2-stable
        given()
            .put("{podId}/slices/{sliceId}/tags/{sourceTag}/alias/{aliasTag}", podName, sliceName, tagV2, tagAlias)
            .then()
            .statusCode(204)

        // v2-stable must resolve to the exact same SDL as v2 (both point to the same revision)
        val sdlV2 = sdlAtTag(tagV2)
        val sdlAlias = sdlAtTag(tagAlias)

        assertEquals(sdlV2, sdlAlias)

        // The tag list now contains both v2 and v2-stable
        val body = given()
            .accept(RDFMediaTypes.JSON_LD)
            .get("{podId}/slices/{sliceId}/tags", podName, sliceName)
            .then()
            .statusCode(200)
            .extract().body().asString()

        @Suppress("UNCHECKED_CAST")
        val graph = (JsonUtils.fromString(body) as Map<String, Any>)[JsonLdKeywords.graph] as List<Map<String, Any>>

        assertTrue(graph.any { tagOf(it) == tagV2 })
        assertTrue(graph.any { tagOf(it) == tagAlias })
    }

    /** Fetches the slice at the given tag via JSON-LD and returns the embedded SDL. */
    private fun sdlAtTag(tag: String): String {
        val slice = given()
            .accept(RDFMediaTypes.JSON_LD)
            .get("{podId}/slices/{sliceId}/tags/{tag}", podName, sliceName, tag)
            .then()
            .statusCode(200)
            .extract().body().asString()
            .let { JsonLdHelper.decode(it, Slice::class.java) }
        return (slice.schema as EmbeddedSliceSchema).sdl
    }

    /** Extracts the single tag string from a compacted JSON-LD EntityTag item. */
    private fun tagOf(obj: Map<String, Any>): String? = obj["kss:tag"] as? String
}
