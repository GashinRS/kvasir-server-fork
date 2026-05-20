package kvasir.services.api.kg.query

import io.quarkus.test.common.http.TestHTTPEndpoint
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.kg.QueryResult
import kvasir.definitions.kg.changes.ChangeStatusCode
import kvasir.definitions.kg.graphql.FIELD_ID_NAME
import kvasir.definitions.kg.slices.EmbeddedSliceSchema
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.utils.test.commons.AbstractPodTest
import kvasir.utils.test.commons.TestConstants
import kvasir.utils.test.commons.getDataField
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * Tests for Slice mutations containing multiple different operations in a single GraphQL request.
 *
 * These tests verify that Kvasir correctly handles mutation documents with more than one
 * top-level mutation field (e.g. an insert followed by a delete, or multiple inserts of
 * different types) within the same request.
 */
@QuarkusTest
@TestHTTPEndpoint(GraphSlicesApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SliceMultiOperationMutationTest : AbstractPodTest() {


    @Inject
    lateinit var repositoryFactory: RepositoryFactory

    private val sliceName = "multi-op-test"
    private lateinit var sliceUri: String

    private val emailDomain = "@multi-op-test.org"

    @BeforeAll
    fun setupSlice() {
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
                so_email: [String!]! @filter(if: "it==*$emailDomain")
            }
            
            input PersonInput @class(iri: "ex:Person") {
                id: ID!
                so_givenName: String!
                so_familyName: String!
                so_email: [String!] @shape(pattern: "$emailDomain")
            }
        """.trimIndent()

        repositoryFactory.getVersionedRepository(Slice::class, podUri).persist(
            Slice(
                id = sliceUri,
                name = sliceName,
                description = "",
                createdBy = "alice",
                context = TestConstants.CONTEXT,
                schema = EmbeddedSliceSchema(sliceDefinition)
            )
        ).await().indefinitely()
    }

    private fun personInput(id: String, givenName: String, familyName: String, email: String): String {
        return """
            {
                id: "$id"
                so_givenName: "$givenName"
                so_familyName: "$familyName"
                so_email: ["$email"]
            }
        """.trimIndent()
    }

    /**
     * A single mutation request with two insertPerson operations (using aliases).
     * Both inserts should produce separate change request IDs and both persons should
     * be committed to the KG.
     */
    @Test
    @TestSecurity(user = "alice")
    @Order(1)
    fun testMultipleInsertOperationsInSingleMutation() {
        val personAId = "ex:multiOpA"
        val personBId = "ex:multiOpB"

        val mutation = """
            mutation {
              first: insertPerson(input: ${personInput(personAId, "Alice", "Anderson", "alice$emailDomain")})
              second: insertPerson(input: ${personInput(personBId, "Bob", "Baker", "bob$emailDomain")})
            }
        """.trimIndent()

        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(mutation))
            .post("{podId}/slices/{sliceId}/query", podName, sliceName)
            .then().statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        // Both aliased fields should be present and return change request IDs
        val firstChangeId = result.getDataField<String>("first")
        val secondChangeId = result.getDataField<String>("second")
        assertNotNull(firstChangeId, "First insert operation should return a change request ID")
        assertNotNull(secondChangeId, "Second insert operation should return a change request ID")

        // Both aliases map to the same atomic ChangeRequest
        assertEquals(
            firstChangeId,
            secondChangeId,
            "All mutation fields in one request should share the same change request ID"
        )

        // Wait for the single atomic change request to be committed
        testHelpers.waitForChangeRequest(firstChangeId!!, podUri, ChangeStatusCode.COMMITTED).await().indefinitely()

        // Both persons should be retrievable
        val queryResult = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl("{ persons { id so_givenName familyName } }"))
            .post("{podId}/slices/{sliceId}/query", podName, sliceName)
            .then().statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        val personIds = queryResult.getDataField<List<Map<String, Any>>>("persons")!!
            .map { JsonLdHelper.compactUri(it[FIELD_ID_NAME] as String, TestConstants.CONTEXT) }.toSet()
        assertTrue(personIds.contains(personAId), "Person A should be present in the KG after multi-insert")
        assertTrue(personIds.contains(personBId), "Person B should be present in the KG after multi-insert")
    }

    /**
     * A single mutation request that inserts a person and then deletes another person.
     * Both operations should be processed: the insert should commit and the delete should commit.
     */
    @Test
    @TestSecurity(user = "alice")
    @Order(2)
    fun testInsertAndDeleteInSingleMutation() {
        // First, insert a person to delete later
        val toDeleteId = "ex:multiOpDelete"
        val insertMutation = """
            mutation {
              insertPerson(input: ${personInput(toDeleteId, "Charlie", "Clark", "charlie$emailDomain")})
            }
        """.trimIndent()

        val insertResult = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(insertMutation))
            .post("{podId}/slices/{sliceId}/query", podName, sliceName)
            .then().statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        val insertChangeId = insertResult.getDataField<String>("insertPerson")!!
        testHelpers.waitForChangeRequest(insertChangeId, podUri, ChangeStatusCode.COMMITTED).await().indefinitely()

        // Now perform a combined mutation: insert a new person AND delete the one we just created
        val toInsertId = "ex:multiOpInsertNew"
        val combinedMutation = """
            mutation {
              add: insertPerson(input: ${personInput(toInsertId, "Diana", "Davis", "diana$emailDomain")})
              remove: deletePerson(input: ${personInput(toDeleteId, "Charlie", "Clark", "charlie$emailDomain")})
            }
        """.trimIndent()

        val combinedResult = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(combinedMutation))
            .post("{podId}/slices/{sliceId}/query", podName, sliceName)
            .then().statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        val addChangeId = combinedResult.getDataField<String>("add")
        val removeChangeId = combinedResult.getDataField<String>("remove")
        assertNotNull(addChangeId, "Insert operation in combined mutation should return a change request ID")
        assertNotNull(removeChangeId, "Delete operation in combined mutation should return a change request ID")

        // Both aliases map to the same atomic ChangeRequest, so their IDs must be identical
        assertEquals(
            addChangeId,
            removeChangeId,
            "All mutation fields in one request should share the same change request ID"
        )

        // Wait for the single atomic change request to be committed
        testHelpers.waitForChangeRequest(addChangeId!!, podUri, ChangeStatusCode.COMMITTED).await().indefinitely()

        // Verify: the new person should exist, the deleted person should not
        val queryResult = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl("{ persons { id so_givenName } }"))
            .post("{podId}/slices/{sliceId}/query", podName, sliceName)
            .then().statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        val personIds = queryResult.getDataField<List<Map<String, Any>>>("persons")!!
            .map { JsonLdHelper.compactUri(it[FIELD_ID_NAME] as String, TestConstants.CONTEXT) }.toSet()
        assertTrue(personIds.contains(toInsertId), "Newly inserted person should be present after combined mutation")
        assertFalse(
            personIds.contains(toDeleteId),
            "Deleted person should no longer be present after combined mutation"
        )
    }

    /**
     * A mutation containing multiple operations where one has an invalid shape should fail
     * atomically — no operations from the request should be committed.
     * This verifies that the entire GraphQL mutation request is treated as a single atomic
     * ChangeRequest: if any operation violates the slice schema, the whole request is rejected.
     */
    @Test
    @TestSecurity(user = "alice")
    @Order(3)
    fun testAtomicFailureWhenAnyOperationIsInvalid() {
        val validPersonId = "ex:multiOpAtomicValid"
        val invalidPersonId = "ex:multiOpAtomicInvalid"

        val mutation = """
            mutation {
              valid: insertPerson(input: ${personInput(validPersonId, "Eve", "Ellis", "eve$emailDomain")})
              invalid: insertPerson(input: ${personInput(invalidPersonId, "Frank", "Foster", "frank@wrong-domain.org")})
            }
        """.trimIndent()

        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(mutation))
            .post("{podId}/slices/{sliceId}/query", podName, sliceName)
            .then().statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        // Both aliases should return change IDs (they map to the same atomic ChangeRequest)
        val validChangeId = result.getDataField<String>("valid")
        val invalidChangeId = result.getDataField<String>("invalid")
        assertNotNull(validChangeId, "Valid operation should return a change request ID")
        assertNotNull(invalidChangeId, "Invalid operation should return a change request ID")
        assertEquals(
            validChangeId,
            invalidChangeId,
            "All mutation fields in one request should share the same change request ID"
        )

        // The entire change request should fail validation because of the invalid operation
        testHelpers.waitForChangeRequest(validChangeId!!, podUri, ChangeStatusCode.VALIDATION_ERROR).await()
            .indefinitely()

        // Neither person should be present in the KG — the whole mutation was rejected
        val queryResult = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl("{ persons { id so_givenName } }"))
            .post("{podId}/slices/{sliceId}/query", podName, sliceName)
            .then().statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        val personIds = queryResult.getDataField<List<Map<String, Any>>>("persons")!!
            .map { JsonLdHelper.compactUri(it[FIELD_ID_NAME] as String, TestConstants.CONTEXT) }.toSet()
        assertFalse(
            personIds.contains(validPersonId),
            "Valid person should NOT be present — entire mutation was rejected"
        )
        assertFalse(personIds.contains(invalidPersonId), "Invalid person should not be present")
    }

    /**
     * A mutation with multiple aliased operations using GraphQL variables.
     * This tests that variable resolution works correctly when multiple mutation fields
     * reference different variables in the same request.
     */
    @Test
    @TestSecurity(user = "alice")
    @Order(4)
    fun testMultipleOperationsWithVariables() {
        val personAId = "ex:varOpA"
        val personBId = "ex:varOpB"

        val mutation = """
            mutation(${'$'}inputA: PersonInput, ${'$'}inputB: PersonInput) {
              first: insertPerson(input: ${'$'}inputA)
              second: insertPerson(input: ${'$'}inputB)
            }
        """.trimIndent()

        val variables = mapOf(
            "inputA" to mapOf(
                "id" to personAId,
                "so_givenName" to "Ivy",
                "so_familyName" to "Ingham",
                "so_email" to listOf("ivy$emailDomain")
            ),
            "inputB" to mapOf(
                "id" to personBId,
                "so_givenName" to "Jake",
                "so_familyName" to "Jones",
                "so_email" to listOf("jake$emailDomain")
            )
        )

        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(mutation, variables = variables))
            .post("{podId}/slices/{sliceId}/query", podName, sliceName)
            .then().statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        val firstChangeId = result.getDataField<String>("first")
        val secondChangeId = result.getDataField<String>("second")
        assertNotNull(firstChangeId, "First variable-based insert should return a change request ID")
        assertNotNull(secondChangeId, "Second variable-based insert should return a change request ID")
        assertEquals(firstChangeId, secondChangeId, "All mutation fields should share the same change request ID")

        testHelpers.waitForChangeRequest(firstChangeId!!, podUri, ChangeStatusCode.COMMITTED).await().indefinitely()

        // Both persons should be retrievable
        val queryResult = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl("{ persons { id so_givenName } }"))
            .post("{podId}/slices/{sliceId}/query", podName, sliceName)
            .then().statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        val personIds = queryResult.getDataField<List<Map<String, Any>>>("persons")!!
            .map { JsonLdHelper.compactUri(it[FIELD_ID_NAME] as String, TestConstants.CONTEXT) }.toSet()
        assertTrue(personIds.contains(personAId), "Person A (via variable) should be present")
        assertTrue(personIds.contains(personBId), "Person B (via variable) should be present")
    }

    /**
     * A document containing multiple named mutation operations, each executed separately
     * via the operationName parameter. This verifies that Kvasir correctly passes the
     * operationName to the GraphQL execution engine and only executes the selected operation.
     */
    @Test
    @TestSecurity(user = "alice")
    @Order(5)
    fun testNamedOperationSelection() {
        val personForOpA = "ex:namedOpA"
        val personForOpB = "ex:namedOpB"

        val mutationDoc = """
            mutation InsertFirst {
              insertPerson(input: ${personInput(personForOpA, "Grace", "Green", "grace$emailDomain")})
            }
            mutation InsertSecond {
              insertPerson(input: ${personInput(personForOpB, "Hank", "Hill", "hank$emailDomain")})
            }
        """.trimIndent()

        // Execute only the first named operation
        val resultA = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(mutationDoc, operationName = "InsertFirst"))
            .post("{podId}/slices/{sliceId}/query", podName, sliceName)
            .then().statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        val changeIdA = resultA.getDataField<String>("insertPerson")
        assertNotNull(changeIdA, "Named operation InsertFirst should return a change request ID")
        testHelpers.waitForChangeRequest(changeIdA!!, podUri, ChangeStatusCode.COMMITTED).await().indefinitely()

        // Execute only the second named operation
        val resultB = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(mutationDoc, operationName = "InsertSecond"))
            .post("{podId}/slices/{sliceId}/query", podName, sliceName)
            .then().statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        val changeIdB = resultB.getDataField<String>("insertPerson")
        assertNotNull(changeIdB, "Named operation InsertSecond should return a change request ID")
        testHelpers.waitForChangeRequest(changeIdB!!, podUri, ChangeStatusCode.COMMITTED).await().indefinitely()

        // The two requests are independent — they should have different change request IDs
        assertNotEquals(changeIdA, changeIdB, "Separate requests should produce different change request IDs")

        // Both persons should now exist
        val queryResult = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl("{ persons { id so_givenName } }"))
            .post("{podId}/slices/{sliceId}/query", podName, sliceName)
            .then().statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        val personIds = queryResult.getDataField<List<Map<String, Any>>>("persons")!!
            .map { JsonLdHelper.compactUri(it[FIELD_ID_NAME] as String, TestConstants.CONTEXT) }.toSet()
        assertTrue(personIds.contains(personForOpA), "Person from InsertFirst should be present")
        assertTrue(personIds.contains(personForOpB), "Person from InsertSecond should be present")
    }
}

