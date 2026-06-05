package kvasir.services.api.kg.query

import io.quarkus.test.common.http.TestHTTPEndpoint
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.kg.ChangeFinalizeRequest
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.definitions.kg.QueryResult
import kvasir.definitions.kg.changes.ChangeRequest
import kvasir.definitions.kg.changes.ChangeStatusCode
import kvasir.definitions.kg.slices.EmbeddedSliceSchema
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.utils.idgen.ChangeRequestId
import kvasir.utils.idgen.StateId
import kvasir.utils.test.commons.AbstractPodTest
import kvasir.utils.test.commons.TestConstants
import kvasir.utils.test.commons.getDataField
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Integration tests verifying that post-assertions are correctly generated and enforced
 * for update mutations that would violate constraints from the corresponding insert type.
 *
 * Covers:
 * - Attempting to null out a field declared non-null in the insert input type → `ASSERTION_FAILED`
 * - `@shape(minLength)` violation when setting a string shorter than the minimum → `ASSERTION_FAILED`
 * - `@shape(maxInclusive)` violation when incrementing past the maximum → `ASSERTION_FAILED`
 * - Successful update that passes all constraint checks → `COMMITTED`
 * - Combined add + update in a single GraphQL mutation document → both succeed
 */
@QuarkusTest
@TestHTTPEndpoint(GraphSlicesApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SliceUpdateConstraintTest : AbstractPodTest() {

    @Inject
    lateinit var kg: KnowledgeGraph

    @Inject
    lateinit var repositoryFactory: RepositoryFactory

    // ── Person slice — non-null and @shape(minLength) constraints ─────────────

    private val personSliceName = "constraint-person"
    private lateinit var personSliceUri: String
    private val personId = "http://example.org/constraint-person-1"

    // ── Counter slice — @shape(maxInclusive) + increment ─────────────────────

    private val counterSliceName = "constraint-counter"
    private lateinit var counterSliceUri: String
    private val counterId = "http://example.org/constraint-counter-1"

    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @TestSecurity(user = "alice")
    fun setupPersonSliceAndData() {
        personSliceUri = "$podUri/slices/$personSliceName"
        // so_givenName is String! with minLength:2 in the insert type
        // so_familyName is String! (non-null, no shape constraint)
        val sliceDefinition = """
            type Query {
                persons: [ex_Person!]!
            }
            type Mutation {
                add(person: [PersonInput!]!): ID!
                update(person: [PersonUpdateInput!]!): ID!
            }
            type ex_Person {
                id: ID!
                ex_givenName: String!
                ex_familyName: String!
            }
            input PersonInput @class(iri: "ex:Person") {
                id: ID!
                ex_givenName: String! @shape(minLength: 2)
                ex_familyName: String!
            }
            input PersonUpdateInput @class(iri: "ex:Person") {
                id: ID!
                ex_givenName: String
                ex_familyName: String
            }
        """.trimIndent()

        repositoryFactory.getVersionedRepository(Slice::class, podUri).persist(
            Slice(
                id = personSliceUri,
                name = personSliceName,
                description = "",
                createdBy = "alice",
                context = TestConstants.CONTEXT,
                schema = EmbeddedSliceSchema(sliceDefinition)
            )
        ).await().indefinitely()

        val personData = mutableMapOf<String, Any>(
            JsonLdKeywords.id to personId,
            JsonLdKeywords.type to "http://example.org/Person",
            "http://example.org/givenName" to "Alice",
            "http://example.org/familyName" to "Smith"
        )
        kg.process(
            ChangeRequest(
                id = ChangeRequestId.generate("$podUri/changes").encode(),
                changeId = StateId.generate(),
                context = emptyMap(),
                requestingUser = "alice",
                podId = podUri,
                insert = listOf(personData)
            )
        ).chain { processedChange -> kg.finalize(ChangeFinalizeRequest(podUri, processedChange.id)) }.await().indefinitely()
    }

    @Test
    @Order(2)
    @TestSecurity(user = "alice")
    fun setupCounterSliceAndData() {
        counterSliceUri = "$podUri/slices/$counterSliceName"
        // ex_score must stay <= 100 per the insert type @shape(maxInclusive)
        val sliceDefinition = """
            type Query {
                counters: [ex_Counter!]!
            }
            type Mutation {
                add(counter: [CounterInput!]!): ID!
                update(counter: [CounterUpdateInput!]!): ID!
            }
            type ex_Counter {
                id: ID!
                ex_score: Int!
            }
            input CounterInput @class(iri: "ex:Counter") {
                id: ID!
                ex_score: Int! @shape(maxInclusive: "100")
            }
            input CounterUpdateInput @class(iri: "ex:Counter") {
                id: ID!
                ex_score: _UpdatableInt
            }
        """.trimIndent()

        repositoryFactory.getVersionedRepository(Slice::class, podUri).persist(
            Slice(
                id = counterSliceUri,
                name = counterSliceName,
                description = "",
                createdBy = "alice",
                context = TestConstants.CONTEXT,
                schema = EmbeddedSliceSchema(sliceDefinition)
            )
        ).await().indefinitely()

        val counterData = mutableMapOf<String, Any>(
            JsonLdKeywords.id to counterId,
            JsonLdKeywords.type to "http://example.org/Counter",
            "http://example.org/score" to 95
        )
        kg.process(
            ChangeRequest(
                id = ChangeRequestId.generate("$podUri/changes").encode(),
                changeId = StateId.generate(),
                context = emptyMap(),
                requestingUser = "alice",
                podId = podUri,
                insert = listOf(counterData)
            )
        ).chain { processedChange -> kg.finalize(ChangeFinalizeRequest(podUri, processedChange.id)) }.await().indefinitely()
    }

    // ── Non-null constraint enforcement ───────────────────────────────────────

    @Test
    @Order(3)
    @TestSecurity(user = "alice")
    fun `nulling out a non-null field triggers validation error`() {
        // ex_familyName is String! in PersonInput — removing it should fail
        val mutation = """
            mutation {
              update(person: { id: "$personId", ex_familyName: null })
            }
        """.trimIndent()

        val changeId = executeMutation(mutation, personSliceUri, "update")
        // Validator generates a POST assertion checking ex_familyName still exists after deletion
        testHelpers.waitForChangeRequest(changeId, podUri, ChangeStatusCode.VALIDATION_ERROR)
            .await().indefinitely()
    }

    // ── @shape(minLength) enforcement ─────────────────────────────────────────

    @Test
    @Order(4)
    @TestSecurity(user = "alice")
    fun `setting a string shorter than minLength triggers validation error`() {
        // ex_givenName has @shape(minLength: 2) — setting it to a single character should fail
        val mutation = """
            mutation {
              update(person: { id: "$personId", ex_givenName: "A" })
            }
        """.trimIndent()

        val changeId = executeMutation(mutation, personSliceUri, "update")
        // Validator generates a POST assertion with the minLength filter — "A" won't match
        testHelpers.waitForChangeRequest(changeId, podUri, ChangeStatusCode.VALIDATION_ERROR)
            .await().indefinitely()
    }

    @Test
    @Order(5)
    @TestSecurity(user = "alice")
    fun `setting a string that meets minLength succeeds`() {
        // "Ali" has length 3 >= 2
        val mutation = """
            mutation {
              update(person: { id: "$personId", ex_givenName: "Ali" })
            }
        """.trimIndent()

        val changeId = executeMutation(mutation, personSliceUri, "update")
        testHelpers.waitForChangeRequest(changeId, podUri, ChangeStatusCode.COMMITTED)
            .await().indefinitely()

        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl("{ persons(id: \"$personId\") { ex_givenName } }"))
            .post("$personSliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)
        val name = result.getDataField<List<Map<String, Any>>>("persons")!![0]["ex_givenName"]
        assertEquals("Ali", name)
    }

    // ── @shape(maxInclusive) enforcement via _increment ──────────────────────

    @Test
    @Order(6)
    @TestSecurity(user = "alice")
    fun `incrementing past maxInclusive triggers validation error`() {
        // ex_score is 95; incrementing by 10 would give 105 > 100
        val mutation = """
            mutation {
              update(counter: { id: "$counterId", ex_score: { _increment: 10 } })
            }
        """.trimIndent()

        val changeId = executeMutation(mutation, counterSliceUri, "update")
        // Validator generates a POST assertion checking ex_score <= 100 — 105 won't pass
        testHelpers.waitForChangeRequest(changeId, podUri, ChangeStatusCode.VALIDATION_ERROR)
            .await().indefinitely()
    }

    @Test
    @Order(7)
    @TestSecurity(user = "alice")
    fun `incrementing within maxInclusive succeeds`() {
        // ex_score is 95; incrementing by 3 gives 98 <= 100
        val mutation = """
            mutation {
              update(counter: { id: "$counterId", ex_score: { _increment: 3 } })
            }
        """.trimIndent()

        val changeId = executeMutation(mutation, counterSliceUri, "update")
        testHelpers.waitForChangeRequest(changeId, podUri, ChangeStatusCode.COMMITTED)
            .await().indefinitely()

        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl("{ counters(id: \"$counterId\") { ex_score } }"))
            .post("$counterSliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)
        val score = (result.getDataField<List<Map<String, Any>>>("counters")!![0]["ex_score"] as Number).toInt()
        assertEquals(98, score)
    }

    // ── Combined add + update in one mutation document ────────────────────────

    @Test
    @Order(8)
    @TestSecurity(user = "alice")
    fun `add and update in a single mutation document both succeed`() {
        val newPersonId = "http://example.org/constraint-person-new"

        // Single mutation document: add a new person AND update the existing one
        val mutation = """
            mutation {
              add(person: {
                id: "$newPersonId"
                ex_givenName: "Bob"
                ex_familyName: "Builder"
              })
              update(person: {
                id: "$personId"
                ex_familyName: "Johnson"
              })
            }
        """.trimIndent()

        // The response carries a single change ID for the whole document
        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(mutation))
            .post("$personSliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)
        // Either mutation field returns the ID — use whichever is present
        val changeId = result.getDataField<String>("add") ?: result.getDataField<String>("update")!!
        testHelpers.waitForChangeRequest(changeId, podUri, ChangeStatusCode.COMMITTED)
            .await().indefinitely()

        // Verify the new person exists
        val newPersonResult = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl("{ persons(id: \"$newPersonId\") { id ex_givenName ex_familyName } }"))
            .post("$personSliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)
        val newPersons = newPersonResult.getDataField<List<Map<String, Any>>>("persons")!!
        assertEquals(1, newPersons.size)
        assertEquals("Bob", newPersons[0]["ex_givenName"])

        // Verify the existing person's familyName was updated
        val existingPersonResult = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl("{ persons(id: \"$personId\") { ex_familyName } }"))
            .post("$personSliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)
        val existingPersons = existingPersonResult.getDataField<List<Map<String, Any>>>("persons")!!
        assertEquals("Johnson", existingPersons[0]["ex_familyName"])
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun executeMutation(mutation: String, sliceUri: String, field: String): String {
        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(mutation))
            .post("$sliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)
        return result.getDataField<String>(field)!!
    }
}



