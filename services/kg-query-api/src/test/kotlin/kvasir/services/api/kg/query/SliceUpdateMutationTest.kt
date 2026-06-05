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
import kvasir.definitions.kg.graphql.FIELD_ID_NAME
import kvasir.definitions.kg.slices.EmbeddedSliceSchema
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.utils.idgen.ChangeRequestId
import kvasir.utils.idgen.StateId
import kvasir.utils.test.commons.AbstractPodTest
import kvasir.utils.test.commons.SchemaVocab
import kvasir.utils.test.commons.TestConstants
import kvasir.utils.test.commons.getDataField
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

@QuarkusTest
@TestHTTPEndpoint(GraphSlicesApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SliceUpdateMutationTest : AbstractPodTest() {

    @Inject
    lateinit var kg: KnowledgeGraph

    @Inject
    lateinit var repositoryFactory: RepositoryFactory

    val sliceName = "update-test"
    lateinit var sliceUri: String

    val testSubject = "http://example.org/alice-update"

    @Test
    @Order(1)
    @TestSecurity(user = "alice")
    fun setupSliceAndData() {
        sliceUri = "$podUri/slices/$sliceName"
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
                so_givenName: String!
                so_familyName: String!
                so_email: [String!]
            }

            input PersonInput @class(iri: "ex:Person") {
                id: ID!
                so_givenName: String!
                so_familyName: String!
                so_email: [String!]
            }

            input PersonUpdateInput @class(iri: "ex:Person") {
                id: ID!
                so_givenName: String
                so_familyName: String
                so_email: [String!]
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

        // Insert initial data
        val personData = mutableMapOf<String, Any>(
            JsonLdKeywords.id to testSubject,
            JsonLdKeywords.type to "http://example.org/Person",
            SchemaVocab.givenName to "Alice",
            SchemaVocab.familyName to "Smith",
            SchemaVocab.email to listOf("alice@example.org", "alice@work.org")
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
        ).chain { processedChange ->
            kg.finalize(ChangeFinalizeRequest(podUri, processedChange.id))
        }.await().indefinitely()

        // Verify the data is there
        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(query = "{ persons(id: \"$testSubject\") { id so_givenName so_familyName so_email } }"))
            .post("{podId}/slices/{sliceId}/query", podName, sliceName)
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)

        val persons = result.getDataField<List<Map<String, Any>>>("persons")!!
        assertEquals(1, persons.size)
        assertEquals(testSubject, persons[0][FIELD_ID_NAME])
    }

    @Test
    @Order(2)
    @TestSecurity(user = "alice")
    fun testUpdateSingleField() {
        // Update only givenName — familyName and email should remain unchanged
        val mutation = """
            mutation {
              update(person: {
                id: "$testSubject"
                so_givenName: "Alicia"
              })
            }
        """.trimIndent()

        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(mutation))
            .post("$sliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)
        val changeId = result.getDataField<String>("update")!!

        testHelpers.waitForChangeRequest(changeId, podUri).await().indefinitely()

        // Verify the update
        val queryResult = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(query = "{ persons(id: \"$testSubject\") { id so_givenName so_familyName so_email } }"))
            .post("{podId}/slices/{sliceId}/query", podName, sliceName)
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)

        val persons = queryResult.getDataField<List<Map<String, Any>>>("persons")!!
        assertEquals(1, persons.size)
        val person = persons[0]
        // givenName should be updated
        assertEquals("Alicia", person["so_givenName"])
        // familyName should remain unchanged
        assertEquals("Smith", person["so_familyName"])
        // email should remain unchanged
        val emails = person["so_email"] as List<*>
        assertEquals(2, emails.size)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "alice")
    fun testUpdateDeleteFieldWithNull() {
        // Set email to null — should remove all emails
        val mutation = """
            mutation {
              update(person: {
                id: "$testSubject"
                so_email: null
              })
            }
        """.trimIndent()

        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(mutation))
            .post("$sliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)
        val changeId = result.getDataField<String>("update")!!

        testHelpers.waitForChangeRequest(changeId, podUri).await().indefinitely()

        // Verify emails are deleted
        val queryResult = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(query = "{ persons(id: \"$testSubject\") { id so_givenName so_email @optional } }"))
            .post("{podId}/slices/{sliceId}/query", podName, sliceName)
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)

        val persons = queryResult.getDataField<List<Map<String, Any>>>("persons")!!
        assertEquals(1, persons.size)
        // givenName should still be "Alicia" from previous test
        assertEquals("Alicia", persons[0]["so_givenName"])
        // email should be empty
        val emails = persons[0]["so_email"] as? List<*>
        assertTrue(emails.isNullOrEmpty())
    }

    @Test
    @Order(4)
    @TestSecurity(user = "alice")
    fun testUpdateCombinedSetAndDelete() {
        // Set familyName to new value AND add new email, in one mutation
        val mutation = """
            mutation {
              update(person: {
                id: "$testSubject"
                so_familyName: "Johnson"
                so_email: ["alice@new.org"]
              })
            }
        """.trimIndent()

        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(mutation))
            .post("$sliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)
        val changeId = result.getDataField<String>("update")!!

        testHelpers.waitForChangeRequest(changeId, podUri).await().indefinitely()

        // Verify
        val queryResult = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(query = "{ persons(id: \"$testSubject\") { id so_givenName so_familyName so_email } }"))
            .post("{podId}/slices/{sliceId}/query", podName, sliceName)
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)

        val persons = queryResult.getDataField<List<Map<String, Any>>>("persons")!!
        assertEquals(1, persons.size)
        val person = persons[0]
        assertEquals("Alicia", person["so_givenName"])
        assertEquals("Johnson", person["so_familyName"])
        assertEquals(listOf("alice@new.org"), person["so_email"])
    }

    @Test
    @Order(5)
    @TestSecurity(user = "alice")
    fun testGeneratedUpdateMutations() {
        // Create a Slice with @generateMutations that includes "update"
        val genSliceName = "gen-update-test"
        val genSliceUri = "$podUri/slices/$genSliceName"
        val sliceDefinition = """
            type Query {
                items: [ex_Item!]!
            }

            type ex_Item @generateMutations(operations: ["add", "update"]) {
                id: ID!
                so_givenName: String! @shape(minLength: 2)
                so_familyName: String!
            }
        """.trimIndent()
        val slice = Slice(
            id = genSliceUri,
            name = genSliceName,
            description = "",
            createdBy = "alice",
            context = TestConstants.CONTEXT,
            schema = EmbeddedSliceSchema(sliceDefinition)
        )
        repositoryFactory.getVersionedRepository(Slice::class, podUri).persist(slice).await().indefinitely()

        // Insert data via the add mutation
        val itemId = "http://example.org/item-gen-1"
        val insertMutation = """
            mutation {
              add(ex_Item: {
                id: "$itemId"
                so_givenName: "Original"
                so_familyName: "Name"
              })
            }
        """.trimIndent()
        val insertResult = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(insertMutation))
            .post("$genSliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)
        val insertChangeId = insertResult.getDataField<String>("add")!!
        testHelpers.waitForChangeRequest(insertChangeId, podUri).await().indefinitely()

        // Now update via the generated update mutation
        val updateMutation = """
            mutation {
              update(ex_ItemUpdate: {
                id: "$itemId"
                so_givenName: "Updated"
              })
            }
        """.trimIndent()
        val updateResult = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(updateMutation))
            .post("$genSliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)
        val updateChangeId = updateResult.getDataField<String>("update")!!
        testHelpers.waitForChangeRequest(updateChangeId, podUri).await().indefinitely()

        // Verify
        val queryResult = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(query = "{ items(id: \"$itemId\") { id so_givenName so_familyName } }"))
            .post("{podId}/slices/{sliceId}/query", podName, genSliceName)
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)
        val items = queryResult.getDataField<List<Map<String, Any>>>("items")!!
        assertEquals(1, items.size)
        assertEquals("Updated", items[0]["so_givenName"])
        assertEquals("Name", items[0]["so_familyName"])
    }

    @Test
    @Order(6)
    @TestSecurity(user = "alice")
    fun testUpdateNonExistentResourceFails() {
        // Attempt to update a resource that does not exist — should fail with ASSERTION_FAILED
        val nonExistentId = "http://example.org/does-not-exist"
        val mutation = """
            mutation {
              update(person: {
                id: "$nonExistentId"
                so_givenName: "Ghost"
              })
            }
        """.trimIndent()

        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(mutation))
            .post("$sliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)
        val changeId = result.getDataField<String>("update")!!

        // The change request should fail because the resource doesn't exist (PRE assertion fails)
        testHelpers.waitForChangeRequest(changeId, podUri, ChangeStatusCode.ASSERTION_FAILED).await().indefinitely()
    }
}
