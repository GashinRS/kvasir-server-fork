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
import kvasir.definitions.kg.graphql.FIELD_ID_NAME
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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assertions.assertNull

/**
 * Integration tests for the `_Updatable*` built-in input types used in update mutations.
 *
 * Covers:
 * - `_UpdatableInt` — `_increment`, `_decrement`, `_set`, `_set: null`
 * - `_UpdatableFloat` — `_multiply`
 * - `_UpdatableString` — `_set`, `_append`, `_prepend`, `_template`
 * - `_UpdatableStringArray` — `_add`, `_remove`, `_set`, `_set: null`
 *
 * Each test verifies the compiled ChangeRequest is accepted by the pipeline and the resulting
 * data reflects the atomic operation semantics.
 */
@QuarkusTest
@TestHTTPEndpoint(GraphSlicesApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SliceUpdatableTypesTest : AbstractPodTest() {

    @Inject
    lateinit var kg: KnowledgeGraph

    @Inject
    lateinit var repositoryFactory: RepositoryFactory

    // ── Counter slice (Int / Float operations) ────────────────────────────────

    private val counterSliceName = "updatable-counter"
    private lateinit var counterSliceUri: String
    private val counterId = "http://example.org/counter-1"

    // ── Person slice (String / StringArray operations) ────────────────────────

    private val personSliceName = "updatable-person"
    private lateinit var personSliceUri: String
    private val personId = "http://example.org/updatable-person-1"

    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @TestSecurity(user = "alice")
    fun setupCounterSliceAndData() {
        counterSliceUri = "$podUri/slices/$counterSliceName"
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
                ex_value: Int!
                ex_ratio: Float
            }
            input CounterInput @class(iri: "ex:Counter") {
                id: ID!
                ex_value: Int!
                ex_ratio: Float
            }
            input CounterUpdateInput @class(iri: "ex:Counter") {
                id: ID!
                ex_value: _UpdatableInt
                ex_ratio: _UpdatableFloat
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

        // Seed: counter with value=10, ratio=1.0
        val counterData = mutableMapOf<String, Any>(
            JsonLdKeywords.id to counterId,
            JsonLdKeywords.type to "http://example.org/Counter",
            "http://example.org/value" to 10,
            "http://example.org/ratio" to 1.0
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

        val result1 = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl("{ counters(id: \"$counterId\") { id ex_value } }"))
            .post("$counterSliceUri/query")
            .then().extract().asString()

        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl("{ counters(id: \"$counterId\") { id ex_value } }"))
            .post("$counterSliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)

        val counters = result.getDataField<List<Map<String, Any>>>("counters")!!
        assertEquals(1, counters.size)
        assertEquals(counterId, counters[0][FIELD_ID_NAME])
    }

    @Test
    @Order(2)
    @TestSecurity(user = "alice")
    fun setupPersonSliceAndData() {
        personSliceUri = "$podUri/slices/$personSliceName"
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
                ex_bio: String
                ex_tags: [String!]
            }
            input PersonInput @class(iri: "ex:Person") {
                id: ID!
                ex_bio: String
                ex_tags: [String!]
            }
            input PersonUpdateInput @class(iri: "ex:Person") {
                id: ID!
                ex_bio: _UpdatableString
                ex_tags: _UpdatableStringArray
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

        // Seed: bio="Hello", tags=["kotlin", "rdf"]
        val personData = mutableMapOf<String, Any>(
            JsonLdKeywords.id to personId,
            JsonLdKeywords.type to "http://example.org/Person",
            "http://example.org/bio" to "Hello",
            "http://example.org/tags" to listOf("kotlin", "rdf")
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

        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl("{ persons(id: \"$personId\") { id ex_bio ex_tags } }"))
            .post("$personSliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)

        val persons = result.getDataField<List<Map<String, Any>>>("persons")!!
        assertEquals(1, persons.size)
        assertEquals(personId, persons[0][FIELD_ID_NAME])
    }

    // ── _UpdatableInt ─────────────────────────────────────────────────────────

    @Test
    @Order(3)
    @TestSecurity(user = "alice")
    fun `_UpdatableInt _increment adds to current value`() {
        val mutation = """
            mutation {
              update(counter: { id: "$counterId", ex_value: { _increment: 5 } })
            }
        """.trimIndent()

        val changeId = executeMutation(mutation, counterSliceUri)
        testHelpers.waitForChangeRequest(changeId, podUri).await().indefinitely()

        // 10 + 5 = 15
        val value = queryCounterValue()
        assertEquals(15, value)
    }

    @Test
    @Order(4)
    @TestSecurity(user = "alice")
    fun `_UpdatableInt _decrement subtracts from current value`() {
        val mutation = """
            mutation {
              update(counter: { id: "$counterId", ex_value: { _decrement: 3 } })
            }
        """.trimIndent()

        val changeId = executeMutation(mutation, counterSliceUri)
        testHelpers.waitForChangeRequest(changeId, podUri).await().indefinitely()

        // 15 - 3 = 12
        val value = queryCounterValue()
        assertEquals(12, value)
    }

    @Test
    @Order(5)
    @TestSecurity(user = "alice")
    fun `_UpdatableInt _set replaces current value`() {
        val mutation = """
            mutation {
              update(counter: { id: "$counterId", ex_value: { _set: 42 } })
            }
        """.trimIndent()

        val changeId = executeMutation(mutation, counterSliceUri)
        testHelpers.waitForChangeRequest(changeId, podUri).await().indefinitely()

        val value = queryCounterValue()
        assertEquals(42, value)
    }

    // ── _UpdatableFloat ───────────────────────────────────────────────────────

    @Test
    @Order(6)
    @TestSecurity(user = "alice")
    fun `_UpdatableFloat _multiply scales current value`() {
        val mutation = """
            mutation {
              update(counter: { id: "$counterId", ex_ratio: { _multiply: 2.5 } })
            }
        """.trimIndent()

        val changeId = executeMutation(mutation, counterSliceUri)
        testHelpers.waitForChangeRequest(changeId, podUri).await().indefinitely()

        // 1.0 * 2.5 = 2.5
        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl("{ counters(id: \"$counterId\") { ex_ratio @optional } }"))
            .post("$counterSliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)
        val ratio = result.getDataField<List<Map<String, Any>>>("counters")!![0]["ex_ratio"] as Number
        assertEquals(2.5, ratio.toDouble(), 0.001)
    }

    // ── _UpdatableString ──────────────────────────────────────────────────────

    @Test
    @Order(7)
    @TestSecurity(user = "alice")
    fun `_UpdatableString _set replaces current value`() {
        val mutation = """
            mutation {
              update(person: { id: "$personId", ex_bio: { _set: "World" } })
            }
        """.trimIndent()

        val changeId = executeMutation(mutation, personSliceUri)
        testHelpers.waitForChangeRequest(changeId, podUri).await().indefinitely()

        assertEquals("World", queryPersonBio())
    }

    @Test
    @Order(8)
    @TestSecurity(user = "alice")
    fun `_UpdatableString _append concatenates to current value`() {
        // bio is currently "World"
        val mutation = """
            mutation {
              update(person: { id: "$personId", ex_bio: { _append: "!" } })
            }
        """.trimIndent()

        val changeId = executeMutation(mutation, personSliceUri)
        testHelpers.waitForChangeRequest(changeId, podUri).await().indefinitely()

        assertEquals("World!", queryPersonBio())
    }

    @Test
    @Order(9)
    @TestSecurity(user = "alice")
    fun `_UpdatableString _prepend prepends to current value`() {
        // bio is currently "World!"
        val mutation = """
            mutation {
              update(person: { id: "$personId", ex_bio: { _prepend: "Hello, " } })
            }
        """.trimIndent()

        val changeId = executeMutation(mutation, personSliceUri)
        testHelpers.waitForChangeRequest(changeId, podUri).await().indefinitely()

        assertEquals("Hello, World!", queryPersonBio())
    }

    @Test
    @Order(10)
    @TestSecurity(user = "alice")
    fun `_UpdatableString _template applies JSONata expression`() {
        // bio is "Hello, World!" — replace with uppercased form via template
        val mutation = """
            mutation {
              update(person: { id: "$personId", ex_bio: { _template: "{current} (updated)" } })
            }
        """.trimIndent()

        val changeId = executeMutation(mutation, personSliceUri)
        testHelpers.waitForChangeRequest(changeId, podUri).await().indefinitely()

        assertEquals("Hello, World! (updated)", queryPersonBio())
    }

    @Test
    @Order(11)
    @TestSecurity(user = "alice")
    fun `_UpdatableString _set null removes the value`() {
        val mutation = """
            mutation {
              update(person: { id: "$personId", ex_bio: { _set: null } })
            }
        """.trimIndent()

        val changeId = executeMutation(mutation, personSliceUri)
        testHelpers.waitForChangeRequest(changeId, podUri).await().indefinitely()

        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl("{ persons(id: \"$personId\") { ex_bio @optional } }"))
            .post("$personSliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)
        val bio = result.getDataField<List<Map<String, Any>>>("persons")!![0]["ex_bio"]
        assertNull(bio)
    }

    // ── _UpdatableStringArray ─────────────────────────────────────────────────

    @Test
    @Order(12)
    @TestSecurity(user = "alice")
    fun `_UpdatableStringArray _add appends elements to collection`() {
        // tags are currently ["kotlin", "rdf"]
        val mutation = """
            mutation {
              update(person: { id: "$personId", ex_tags: { _add: ["graphql"] } })
            }
        """.trimIndent()

        val changeId = executeMutation(mutation, personSliceUri)
        testHelpers.waitForChangeRequest(changeId, podUri).await().indefinitely()

        val tags = queryPersonTags()
        assertEquals(3, tags.size)
        assertTrue(tags.containsAll(listOf("kotlin", "rdf", "graphql")))
    }

    @Test
    @Order(13)
    @TestSecurity(user = "alice")
    fun `_UpdatableStringArray _remove removes specific elements`() {
        // tags are currently ["kotlin", "rdf", "graphql"]
        val mutation = """
            mutation {
              update(person: { id: "$personId", ex_tags: { _remove: ["rdf"] } })
            }
        """.trimIndent()

        val changeId = executeMutation(mutation, personSliceUri)
        testHelpers.waitForChangeRequest(changeId, podUri).await().indefinitely()

        val tags = queryPersonTags()
        assertEquals(2, tags.size)
        assertTrue(tags.containsAll(listOf("kotlin", "graphql")))
        assertFalse(tags.contains("rdf"))
    }

    @Test
    @Order(14)
    @TestSecurity(user = "alice")
    fun `_UpdatableStringArray _set replaces entire collection`() {
        val mutation = """
            mutation {
              update(person: { id: "$personId", ex_tags: { _set: ["only-this"] } })
            }
        """.trimIndent()

        val changeId = executeMutation(mutation, personSliceUri)
        testHelpers.waitForChangeRequest(changeId, podUri).await().indefinitely()

        val tags = queryPersonTags()
        assertEquals(listOf("only-this"), tags)
    }

    @Test
    @Order(15)
    @TestSecurity(user = "alice")
    fun `_UpdatableStringArray _set null clears the collection`() {
        val mutation = """
            mutation {
              update(person: { id: "$personId", ex_tags: { _set: null } })
            }
        """.trimIndent()

        val changeId = executeMutation(mutation, personSliceUri)
        testHelpers.waitForChangeRequest(changeId, podUri).await().indefinitely()

        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl("{ persons(id: \"$personId\") { ex_tags @optional } }"))
            .post("$personSliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)
        val tags = result.getDataField<List<Map<String, Any>>>("persons")!![0]["ex_tags"] as? List<*>
        assertTrue(tags.isNullOrEmpty())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun executeMutation(mutation: String, sliceUri: String): String {
        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(mutation))
            .post("$sliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)
        return result.getDataField<String>("update")!!
    }

    private fun queryCounterValue(): Int {
        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl("{ counters(id: \"$counterId\") { ex_value } }"))
            .post("$counterSliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)
        return (result.getDataField<List<Map<String, Any>>>("counters")!![0]["ex_value"] as Number).toInt()
    }

    private fun queryPersonBio(): String? {
        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl("{ persons(id: \"$personId\") { ex_bio @optional } }"))
            .post("$personSliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)
        return result.getDataField<List<Map<String, Any>>>("persons")!![0]["ex_bio"] as? String
    }

    @Suppress("UNCHECKED_CAST")
    private fun queryPersonTags(): List<String> {
        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl("{ persons(id: \"$personId\") { ex_tags @optional } }"))
            .post("$personSliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)
        return result.getDataField<List<Map<String, Any>>>("persons")!![0]["ex_tags"] as? List<String> ?: emptyList()
    }
}




