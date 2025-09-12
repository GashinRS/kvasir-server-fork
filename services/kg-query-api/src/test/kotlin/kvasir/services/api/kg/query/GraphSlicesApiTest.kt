package kvasir.services.api.kg.query

import com.github.jsonldjava.utils.JsonUtils
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.http.TestHTTPEndpoint
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.get
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.kg.*
import kvasir.definitions.kg.graphql.FIELD_ID_NAME
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.definitions.rdf.getJsonArray
import kvasir.utils.idgen.ChangeRequestId
import kvasir.utils.test.clickhouse.ClickhouseTestResource
import kvasir.utils.test.commons.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

@QuarkusTest
@TestHTTPEndpoint(GraphSlicesApi::class)
@QuarkusTestResource(ClickhouseTestResource::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class GraphSlicesApiTest {

    @Inject
    lateinit var kg: KnowledgeGraph

    @Inject
    lateinit var testHelpers: TestHelpers

    val sliceName = "test1"
    val filterEmailDomain = "@slice-test.org"
    lateinit var sliceUri: String

    val testSubject = "ex:jdoe"
    lateinit var revisitChangeId: String

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
            .body(input)
            .post("{podId}/slices", TestConstants.TEST_POD_2_ID)
            .then()
            .statusCode(201)
            .extract().header(HttpHeaders.LOCATION)

        val sliceDef = get(sliceUri).then().statusCode(200).extract().body().`as`(Slice::class.java)
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
            .body(input)
            .post("{podId}/slices", TestConstants.TEST_POD_2_ID)
            .then()
            .statusCode(201)
            .extract().header(HttpHeaders.LOCATION)

        val sliceDef = get(sliceUri).then().statusCode(200).extract().body().`as`(Slice::class.java)
        assertFalse(sliceDef.supportsChanges)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "alice")
    fun testListSlices() {
        val result = JsonUtils.fromString(
            get("{podId}/slices", TestConstants.TEST_POD_2_ID)
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
    @Order(4)
    @TestSecurity(user = "alice")
    fun testSliceQuery() {
        // Populate some data
        val podUri = testHelpers.getPodUri(TestConstants.TEST_POD_2_ID)
        // Generate 15 persons and then another 5 persons with an email ending on the domain specified in the slice filter
        val slicePersonData = TestDataGenerator.generatePersonData(5).map { person ->
            person.apply {
                val firstName = person[SchemaVocab.givenName]!!
                this[SchemaVocab.email] = "$firstName$filterEmailDomain"
            }
        }
        val allPersonData = TestDataGenerator.generatePersonData(15) + slicePersonData
        kg.process(
            ChangeRequest(
                ChangeRequestId.generate("$podUri/changes").encode(),
                emptyMap(),
                "alice",
                podUri,
                insert = allPersonData
            )
        ).await().indefinitely()

        // Query via Slice
        var result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                QueryInputImpl(
                    query = "{ persons { id so_givenName familyName so_email } }"
                )
            )
            .post("{podId}/slices/{sliceId}/query", TestConstants.TEST_POD_2_ID, sliceName)
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)

        // Check if the expected resources are present (by id)
        assertEquals(
            slicePersonData.map { it[JsonLdKeywords.id] }.toSet(),
            result.getDataField<List<Map<String, Any>>>("persons")!!.map { it[FIELD_ID_NAME] }.toSet()
        )

        // Query again, but leave out the so_email field. The result should still only include persons with the specific email domain.
        result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                QueryInputImpl(
                    query = "{ persons { id so_givenName familyName } }"
                )
            )
            .post("{podId}/slices/{sliceId}/query", TestConstants.TEST_POD_2_ID, sliceName)
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)

        // Check if the expected resources are present (by id)
        assertEquals(
            slicePersonData.map { it[JsonLdKeywords.id] }.toSet(),
            result.getDataField<List<Map<String, Any>>>("persons")!!.map { it[FIELD_ID_NAME] }.toSet()
        )
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
            .body(input)
            .put(sliceUri)
            .then().statusCode(204)

        // Fetch the Slice definition, mutations should now be enabled.
        val sliceDef = get(sliceUri).then().statusCode(200).extract().body().`as`(Slice::class.java)
        assertTrue(sliceDef.supportsChanges)
    }

    @Test
    @TestSecurity(user = "alice")
    @Order(6)
    fun testInvalidMutation() {
        // This insert should fail, as the email domain does not match the shape
        val q = """
            mutation {
              insertPerson(input: ${getPersonInput(testSubject, "jdoe@example.org")})
            }
        """.trimIndent()

        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(q))
            .post("$sliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)
        val changeId = result.getDataField<String>("insertPerson")!!
        val podId = testHelpers.getPodUri(TestConstants.TEST_POD_2_ID)

        testHelpers.waitForChangeRequest(changeId, podId, ChangeStatusCode.VALIDATION_ERROR).await()
            .indefinitely()
        val changeRecords = kg.getChangeRecords(ChangeRecordRequest(podId, changeId)).await().indefinitely()
        assertTrue(changeRecords.items.isEmpty())
    }

    private fun getPersonInput(id: String, email: String): String {
        return """
            {
                id: "$id"
                so_givenName: "John"
                so_familyName: "Doe"
                so_email: ["$email"]
            }
        """.trimIndent()
    }

    @Test
    @TestSecurity(user = "alice")
    @Order(7)
    fun testValidMutation() {
        // Use the email domain that matches the shape
        var q = """
            mutation {
              insertPerson(input: ${getPersonInput(testSubject, "jdoe$filterEmailDomain")})
            }
        """.trimIndent()

        var result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(q))
            .post("$sliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)
        var changeId = result.getDataField<String>("insertPerson")!!
        revisitChangeId = changeId
        val podId = testHelpers.getPodUri(TestConstants.TEST_POD_2_ID)

        testHelpers.waitForChangeRequest(changeId, podId, ChangeStatusCode.COMMITTED).await()
            .indefinitely()

        // The added person should be retrievable
        val readQ = """
            {
                person(id: "$testSubject") {
                    id
                    so_givenName
                    familyName
                }
            }
        """.trimIndent()

        result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(readQ))
            .post("$sliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)

        val returnedPersonData = result.getDataField<Map<String, Any>>("person")!!
        assertEquals("John", returnedPersonData["so_givenName"])
        assertEquals("Doe", returnedPersonData["familyName"])

        // Now perform a delete mutation
        q = """
            mutation {
              deletePerson(input: ${getPersonInput(testSubject, "jdoe$filterEmailDomain")})
            }
        """.trimIndent()

        result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(q))
            .post("$sliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)
        changeId = result.getDataField<String>("deletePerson")!!

        testHelpers.waitForChangeRequest(changeId, podId, ChangeStatusCode.COMMITTED).await()
            .indefinitely()

        // The person should no longer be retrievable
        result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(readQ))
            .post("$sliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)
        assertNull(result.getDataField<Map<String, Any>>("person"))
    }

    @Test
    @TestSecurity(user = "alice")
    @Order(8)
    fun testTimeTravel() {
        // Although jdoe was deleted in the previous test, we should still be able to retrieve the data using time travel.
        val readQ = """
            {
                person(id: "$testSubject") {
                    id
                    so_givenName
                    familyName
                }
            }
        """.trimIndent()

        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(readQ, atChangeRequest = revisitChangeId)) // Use atChangeRequest to time travel
            .post("$sliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)

        val returnedPersonData = result.getDataField<Map<String, Any>>("person")!!
        assertEquals("John", returnedPersonData["so_givenName"])
        assertEquals("Doe", returnedPersonData["familyName"])
    }

}