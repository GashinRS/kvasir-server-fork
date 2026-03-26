package kvasir.services.api.pods

import com.github.jsonldjava.utils.JsonUtils
import io.quarkus.test.common.http.TestHTTPEndpoint
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.get
import io.restassured.RestAssured.given
import jakarta.ws.rs.core.HttpHeaders
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
class SliceManagementApiTest: AbstractPodTest() {

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
            schema = sliceDefinition
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
            schema = sliceDefinition
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
            schema = sliceDefinition
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

}