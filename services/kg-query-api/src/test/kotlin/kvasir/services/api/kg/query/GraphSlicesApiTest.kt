package kvasir.services.api.kg.query

import io.quarkus.test.common.http.TestHTTPEndpoint
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.kg.ChangeFinalizeRequest
import kvasir.definitions.kg.ChangeRecordRequest
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.definitions.kg.QueryResult
import kvasir.definitions.kg.changes.ChangeRequest
import kvasir.definitions.kg.changes.ChangeStatusCode
import kvasir.definitions.kg.graphql.FIELD_ID_NAME
import kvasir.definitions.kg.slices.EmbeddedSliceSchema
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.utils.idgen.ChangeRequestId
import kvasir.utils.idgen.StateId
import kvasir.utils.test.commons.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

@QuarkusTest
@TestHTTPEndpoint(GraphSlicesApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class GraphSlicesApiTest : AbstractPodTest() {

    @Inject
    lateinit var kg: KnowledgeGraph

    @Inject
    lateinit var repositoryFactory: RepositoryFactory

    val sliceName = "test1"
    val filterEmailDomain = "@slice-test.org"
    lateinit var sliceUri: String

    val testSubject = "ex:jdoe"
    lateinit var revisitChangeId: String

    lateinit var nonNamedSliceUri: String

    @Test
    @Order(4)
    @TestSecurity(user = "alice")
    fun testSliceQuery() {
        // Init the Slice
        sliceUri = "$podUri/slices/$sliceName"
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
        val slice = Slice(
            id = sliceUri,
            name = sliceName,
            description = "",
            createdBy = "alice",
            context = TestConstants.CONTEXT,
            schema = EmbeddedSliceSchema(sliceDefinition)
        )
        repositoryFactory.getVersionedRepository(Slice::class, podUri).persist(slice).await().indefinitely()


        // Populate some data
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
                id = ChangeRequestId.generate("$podUri/changes").encode(),
                // Arbitrary state id
                changeId = StateId.generate(),
                context = emptyMap(),
                requestingUser = "alice",
                podId = podUri,
                insert = allPersonData
            )
        ).chain { processedChange ->
            // Manually finalize the request
            kg.finalize(ChangeFinalizeRequest(podUri, processedChange.id))
        }.await().indefinitely()

        // Query via Slice
        var result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                QueryInputImpl(
                    query = "{ persons { id so_givenName familyName so_email } }"
                )
            )
            .post("{podId}/slices/{sliceId}/query", podName, sliceName)
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
            .post("{podId}/slices/{sliceId}/query", podName, sliceName)
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)

        // Check if the expected resources are present (by id)
        assertEquals(
            slicePersonData.map { it[JsonLdKeywords.id] }.toSet(),
            result.getDataField<List<Map<String, Any>>>("persons")!!.map { it[FIELD_ID_NAME] }.toSet()
        )
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

        testHelpers.waitForChangeRequest(changeId, podUri, ChangeStatusCode.VALIDATION_ERROR).await()
            .indefinitely()
        val changeRecords = kg.getChangeRecords(ChangeRecordRequest(podUri, changeId)).await().indefinitely()
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
        var changeRequestId = result.getDataField<String>("insertPerson")!!

        revisitChangeId = testHelpers.waitForChangeRequest(changeRequestId, podUri, ChangeStatusCode.COMMITTED).await()
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
        changeRequestId = result.getDataField<String>("deletePerson")!!

        testHelpers.waitForChangeRequest(changeRequestId, podUri, ChangeStatusCode.COMMITTED).await()
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
            .body(QueryInputImpl(readQ, atChangeId = revisitChangeId)) // Use atChangeRequest to time travel
            .post("$sliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)

        val returnedPersonData = result.getDataField<Map<String, Any>>("person")!!
        assertEquals("John", returnedPersonData["so_givenName"])
        assertEquals("Doe", returnedPersonData["familyName"])
    }

    @Test
    @TestSecurity(user = "alice")
    @Order(9)
    fun testTaggedSliceQuery() {
        // Tag the current (latest) version of the existing slice
        val tagName = "stable"
        val currentSlice = repositoryFactory.getVersionedRepository(Slice::class, podUri).findById(sliceUri).await()
            .indefinitely()!!
        repositoryFactory.getVersionedRepository(Slice::class, podUri).persist(currentSlice, setOf(tagName)).await()
            .indefinitely()

        // Query via the tagged endpoint – should return the same filtered persons as the regular query
        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(query = "{ persons { id so_givenName familyName } }"))
            .post("{podId}/slices/{sliceId}/tags/{tag}/query", podName, sliceName, tagName)
            .then()
            .statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        // The slice has a @filter on so_email – only the 5 persons with the specific email domain should be returned
        val persons = result.getDataField<List<Map<String, Any>>>("persons")!!
        assertEquals(5, persons.size)
    }

    @Test
    @TestSecurity(user = "alice")
    @Order(10)
    fun testTaggedSliceQueryWithNonExistentTag() {
        // A non-existent tag must produce a 404
        given()
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(query = "{ persons { id } }"))
            .post("{podId}/slices/{sliceId}/tags/{tag}/query", podName, sliceName, "nonexistent-tag")
            .then()
            .statusCode(404)
    }

    @Test
    @TestSecurity(user = "alice")
    @Order(11)
    fun testTaggedSliceQueryNonExistentSliceReturns404() {
        given()
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(query = "{ persons { id } }"))
            .post("{podId}/slices/{sliceId}/tags/{tag}/query", podName, "nonexistent-slice", "v1")
            .then()
            .statusCode(404)
    }

}