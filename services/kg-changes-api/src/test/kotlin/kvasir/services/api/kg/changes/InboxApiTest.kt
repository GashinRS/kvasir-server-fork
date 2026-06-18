package kvasir.services.api.kg.changes

import io.quarkus.test.common.http.TestHTTPEndpoint
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import kvasir.definitions.kg.ChangeRecordRequest
import kvasir.definitions.kg.ChangeRecordType
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.kg.changes.Assertion
import kvasir.definitions.kg.changes.AssertionPhase
import kvasir.definitions.kg.changes.ChangeStatusCode
import kvasir.definitions.kg.changes.ProcessedChange
import kvasir.definitions.kg.slices.EmbeddedSliceSchema
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.rdf.*
import kvasir.utils.rdf.RDFTransformer
import kvasir.utils.test.commons.AbstractPodTest
import kvasir.utils.test.commons.SchemaVocab
import kvasir.utils.test.commons.TestConstants
import kvasir.utils.test.commons.TestDataGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertNotNull
import java.util.*

@QuarkusTest
@TestHTTPEndpoint(InboxApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InboxApiTest : AbstractPodTest() {

    @Inject
    lateinit var knowledgeGraph: KnowledgeGraph

    @Inject
    lateinit var repositoryFactory: RepositoryFactory

    @Test
    @TestSecurity(user = "alice")
    fun testBasicInsertAndDelete() {
        val personData = TestDataGenerator.generatePersonData(1)
        val insert = ChangeRequestInput(insert = personData)

        // Perform change request for inserts
        val insertChangeRequestUri = testHelpers.requestChangeViaHTTPSync(JsonLdHelper.encode(insert), podUri)

        // Fetch the committed records for the change via KG
        val changeRecords = knowledgeGraph.getChangeRecords(
            ChangeRecordRequest(
                podUri,
                insertChangeRequestUri
            )
        ).await().indefinitely()

        assertEquals(getExpectedChangeRecords(insert), changeRecords.items.map { it.type to it.statement }.toSet())

        // The data should also be retrievable via query
        var result = knowledgeGraph.query(
            QueryRequest(
                TestConstants.CONTEXT,
                "alice",
                podUri,
                query = "{ ex_Person { id so_givenName so_familyName } }"
            )
        ).toUni().await().indefinitely()
        var persons = result.data?.get("ex_Person") as List<Map<String, Any>>
        assertTrue(persons.isNotEmpty())

        // Delete the inserted data
        val delete = ChangeRequestInput(delete = personData)
        // Perform change request for delete
        val deleteChangeRequestUri = testHelpers.requestChangeViaHTTPSync(JsonLdHelper.encode(delete), podUri)

        // Fetch the committed records for the change via KG
        val deleteChangeRecords = knowledgeGraph.getChangeRecords(
            ChangeRecordRequest(
                podUri,
                deleteChangeRequestUri
            )
        ).await().indefinitely()

        assertEquals(
            getExpectedChangeRecords(delete),
            deleteChangeRecords.items.map { it.type to it.statement }.toSet()
        )

        // The data should not be retrievable anymore
        result = knowledgeGraph.query(
            QueryRequest(
                TestConstants.CONTEXT,
                "alice",
                podUri,
                query = "{ ex_Person { id so_givenName so_familyName } }"
            )
        ).toUni().await().indefinitely()
        persons = result.data?.get("ex_Person") as List<Map<String, Any>>
        assertTrue(persons.isEmpty())
    }

    @Test
    @TestSecurity(user = "alice")
    fun testIngestBlankNodes() {
        val changeRequest = """
            {
              "@context": {
                "kss": "https://kvasir.discover.ilabt.imec.be/vocab#"
              },
              "kss:insert": [
                {
                  "@id": "http://example.org/Actors/YiRxGhekmwgCbpknJ",
                  "@type": [
                    "http://example.org/Actor"
                  ],
                  "http://example.org/message": [
                    {
                      "@id": "_:Nfe52679860ad478eba43a7cef1d334ae"
                    }
                  ]
                },
                {
                  "@id": "_:Nfe52679860ad478eba43a7cef1d334ae",
                  "@type": [
                    "http://example.org/Message"
                  ],
                  "http://example.org/body": [
                    {
                      "@value": "Hello World!"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        // Perform change request for inserts
        val insertChangeRequestUri = testHelpers.requestChangeViaHTTPSync(changeRequest, podUri)

        // Fetch the committed records for the change via KG
        val changeRecords = knowledgeGraph.getChangeRecords(
            ChangeRecordRequest(
                podUri,
                insertChangeRequestUri
            )
        ).await().indefinitely()
        val actorMessageStmt =
            changeRecords.items.firstOrNull { it.statement.predicate == "http://example.org/message" }
        val messageBodyStmt = changeRecords.items.firstOrNull { it.statement.predicate == "http://example.org/body" }
        assertNotNull(actorMessageStmt)
        assertNotNull(messageBodyStmt)
        assertTrue(!actorMessageStmt.statement.`object`.let { it as String }.startsWith("_:"))
        assertEquals(actorMessageStmt.statement.`object`, messageBodyStmt.statement.subject)
    }

    @Test
    @TestSecurity(user = "alice")
    fun testAssertions() {
        // Add a person
        val personData = TestDataGenerator.generatePersonData(1)
        val personId = personData.first()[JsonLdKeywords.id]!!
        val insert = ChangeRequestInput(insert = personData)

        // Perform change request for inserts
        testHelpers.requestChangeViaHTTPSync(JsonLdHelper.encode(insert), podUri)

        // Schedule a change request that should only be executed when the specified id does not already exist
        val badInsert =
            ChangeRequestInput(
                context = TestConstants.CONTEXT,
                assert = listOf(
                    Assertion(
                        KvasirVocab.AssertEmptyResult,
                        "{ ex_Person(id: \"$personId\") { id } }"
                    )
                ), insert = personData
            )

        // Perform the request
        testHelpers.requestChangeViaHTTPSync(
            JsonLdHelper.encode(badInsert),
            podUri,
            expectedResult = ChangeStatusCode.ASSERTION_FAILED
        )

        // Schedule a change request that should only be executed when the specified id exists
        val validDelete = ChangeRequestInput(
            context = TestConstants.CONTEXT,
            assert = listOf(
                Assertion(
                    KvasirVocab.AssertNonEmptyResult,
                    "{ ex_Person(id: \"$personId\") { id } }"
                )
            ), delete = personData
        )

        // Perform the request
        testHelpers.requestChangeViaHTTPSync(JsonLdHelper.encode(validDelete), podUri)

        // The data should not be retrievable anymore
        val result = knowledgeGraph.query(
            QueryRequest(
                TestConstants.CONTEXT,
                "alice",
                podUri,
                query = "{ ex_Person { id so_givenName so_familyName } }"
            )
        ).toUni().await().indefinitely()
        val persons = result.data?.get("ex_Person") as List<Map<String, Any>>
        assertTrue(persons.isEmpty())
    }


    @Test
    @TestSecurity(user = "alice")
    fun testPostAssertions() {
        // === Successful POST assertion ===
        // Insert a person with a POST assertion that checks the person now exists
        val personData = TestDataGenerator.generatePersonData(1)
        val personId = personData.first()[JsonLdKeywords.id]!!

        val insertWithPostAssert = ChangeRequestInput(
            context = TestConstants.CONTEXT,
            assert = listOf(
                Assertion(
                    KvasirVocab.AssertNonEmptyResult,
                    "{ ex_Person(id: \"$personId\") { id } }",
                    phase = AssertionPhase.POST
                )
            ),
            insert = personData
        )

        // This should succeed: after inserting, the person exists
        testHelpers.requestChangeViaHTTPSync(JsonLdHelper.encode(insertWithPostAssert), podUri)

        // Verify the person is present
        var result = knowledgeGraph.query(
            QueryRequest(
                TestConstants.CONTEXT,
                "alice",
                podUri,
                query = "{ ex_Person(id: \"$personId\") { id } }"
            )
        ).toUni().await().indefinitely()
        var persons = result.data?.get("ex_Person") as List<Map<String, Any>>
        assertTrue(persons.isNotEmpty())

        // === Failing POST assertion (should rollback) ===
        // Try to insert another person, but assert that no person with the first ID exists (POST)
        // Since we just inserted it, the assertion should fail and the change should be rolled back
        val personData2 = TestDataGenerator.generatePersonData(1)

        val insertWithFailingPostAssert = ChangeRequestInput(
            context = TestConstants.CONTEXT,
            assert = listOf(
                Assertion(
                    KvasirVocab.AssertEmptyResult,
                    "{ ex_Person(id: \"$personId\") { id } }",
                    phase = AssertionPhase.POST
                )
            ),
            insert = personData2
        )

        // This should fail: after inserting, the first person still exists so AssertEmptyResult fails
        testHelpers.requestChangeViaHTTPSync(
            JsonLdHelper.encode(insertWithFailingPostAssert),
            podUri,
            expectedResult = ChangeStatusCode.ASSERTION_FAILED
        )

        // Verify the second person was rolled back (not present)
        val personId2 = personData2.first()[JsonLdKeywords.id]!!
        result = knowledgeGraph.query(
            QueryRequest(
                TestConstants.CONTEXT,
                "alice",
                podUri,
                query = "{ ex_Person(id: \"$personId2\") { id } }"
            )
        ).toUni().await().indefinitely()
        persons = result.data?.get("ex_Person") as List<Map<String, Any>>
        assertTrue(persons.isEmpty(), "Second person should have been rolled back")

        // Clean up: delete the first person
        val delete = ChangeRequestInput(context = TestConstants.CONTEXT, delete = personData)
        testHelpers.requestChangeViaHTTPSync(JsonLdHelper.encode(delete), podUri)
    }

    @Test
    @TestSecurity(user = "alice")
    fun testWithClause() {
        // Add a person
        val personData = TestDataGenerator.generatePersonData(1)
        val originalEmails = personData.first().getJsonArray<String>(SchemaVocab.email)!!.toSet()
        val personId = personData.first()[JsonLdKeywords.id]!!
        val personGivenName = personData.first()[SchemaVocab.givenName]!!
        val insert = ChangeRequestInput(insert = personData)
        val updatedEmail = "$personGivenName@somedomain.org"

        // Perform change request for inserts
        testHelpers.requestChangeViaHTTPSync(JsonLdHelper.encode(insert), podUri)

        // Schedule a change request that updates the email address for the person with the specified givenName
        val update = ChangeRequestInput(
            context = TestConstants.CONTEXT,
            with = "{ ex_Person(so_givenName: \"$personGivenName\"){ id so_email } }",
            delete = listOf(
                """
                {
                    "@id": ex_Person.id,
                    "so:email": ex_Person.so_email
                }
            """.trimIndent()
            ),
            insert = listOf(
                """
                {
                    "@id": ex_Person.id,
                    "so:email": "$updatedEmail"
                }
            """.trimIndent()
            )
        )

        val changeRequestUri = testHelpers.requestChangeViaHTTPSync(JsonLdHelper.encode(update), podUri)

        // Check the changes
        val changes =
            knowledgeGraph.getChangeRecords(
                ChangeRecordRequest(
                    podUri,
                    changeRequestUri
                )
            ).await().indefinitely()

        assertEquals(
            originalEmails,
            changes.items.filter { it.type == ChangeRecordType.DELETE }.map { it.statement.`object` as String }.toSet()
        )
        assertEquals(
            setOf(updatedEmail),
            changes.items.filter { it.type == ChangeRecordType.INSERT }.map { it.statement.`object` as String }.toSet()
        )

        // Check the email address
        val result = knowledgeGraph.query(
            QueryRequest(
                TestConstants.CONTEXT,
                "alice",
                podUri,
                query = "{ ex_Person(id: \"$personId\") { so_email } }"
            )
        ).toUni().await().indefinitely()
        val persons = result.data?.get("ex_Person") as List<Map<String, Any>>
        assertEquals(listOf(updatedEmail), persons.first()["so_email"])

        // Delete the person
        val delete = ChangeRequestInput(
            context = TestConstants.CONTEXT, with = """
            {
              ex_Person(id: "$personId") {
                id
                so_givenName
                so_familyName
                so_email
              }
            }
        """.trimIndent(), delete = listOf(
                """
            {
              "@id": ex_Person.id,
              "@type": "ex:Person",
              "so:givenName": ex_Person.so_givenName,
              "so:familyName": ex_Person.so_familyName,
              "so:email": ex_Person.so_email
            }
        """.trimIndent()
            )
        )
        // Perform change request for delete
        testHelpers.requestChangeViaHTTPSync(JsonLdHelper.encode(delete), podUri)
    }

    @Test
    @TestSecurity(user = "alice")
    fun testSliceInbox() {
        // Define Slice
        val sliceId = "$podUri/slices/test"
        repositoryFactory.getVersionedRepository(Slice::class, podUri).persist(
            Slice(
                sliceId,
                createdBy = "alice",
                TestConstants.CONTEXT,
                "test",
                "",
                EmbeddedSliceSchema(sdl="""
                    type Mutation {
                        insert(input: PersonInput!): ID!
                        delete(input: PersonInput!): ID!
                    }

                    input PersonInput @class(iri: "ex:Person") {
                      id: ID!
                      so_givenName: String!
                      so_familyName: String! @shape(minLength: 2)
                      so_email: [String!]
                    }
                """.trimIndent()),
                supportsChanges = true
            )
        ).await().indefinitely()

        // Post data directly to the inbox (instead of via GraphQL mutation)
        // Data that doesn't match the schema, shouldn't be accepted
        val invalidPersonData = listOf(
            mapOf(
                JsonLdKeywords.id to "ex:${UUID.randomUUID()}",
                JsonLdKeywords.type to "ex:Person",
                SchemaVocab.givenName to "Joe",
                SchemaVocab.familyName to "D"
            )
        )
        val invalidChange = ChangeRequestInput(context = TestConstants.CONTEXT, insert = invalidPersonData)
        testHelpers.requestChangeViaHTTPSync(
            JsonLdHelper.encode(invalidChange),
            podUri,
            ChangeStatusCode.VALIDATION_ERROR,
            sliceId
        )

        val invalidNonPersonData = listOf(
            mapOf(
                JsonLdKeywords.id to "ex:${UUID.randomUUID()}",
                JsonLdKeywords.type to "ex:Cat",
                SchemaVocab.givenName to "Mittens"
            )
        )
        val invalidChange2 = ChangeRequestInput(context = TestConstants.CONTEXT, insert = invalidNonPersonData)
        testHelpers.requestChangeViaHTTPSync(
            JsonLdHelper.encode(invalidChange2),
            podUri,
            ChangeStatusCode.VALIDATION_ERROR,
            sliceId
        )

        // Data that matches should be committed
        val validPersonData = TestDataGenerator.generatePersonData(1)
        val validChange = ChangeRequestInput(context = TestConstants.CONTEXT, insert = validPersonData)
        testHelpers.requestChangeViaHTTPSync(JsonLdHelper.encode(validChange), podUri, sliceUri = sliceId)

        // Delete the data
        val delete = ChangeRequestInput(context = TestConstants.CONTEXT, delete = validPersonData)
        testHelpers.requestChangeViaHTTPSync(JsonLdHelper.encode(delete), podUri, sliceUri = sliceId)
    }

    @Test
    @TestSecurity(user = "alice")
    fun testSyncModeReturnsCommittedChangeDirectly() {
        val personData = TestDataGenerator.generatePersonData(1)
        val insert = ChangeRequestInput(insert = personData)

        // POST with sync=true — should block and return 200 + ProcessedChange body without polling
        val responseBody = given()
            .contentType(RDFMediaTypes.JSON_LD)
            .accept(RDFMediaTypes.JSON_LD)
            .body(JsonLdHelper.encode(insert))
            .queryParam("sync", "true")
            .post("$podUri/changes")
            .then()
            .statusCode(200)
            .extract().body().asString()

        val processedChange = JsonLdHelper.decode(responseBody, ProcessedChange::class.java)
        assertEquals(ChangeStatusCode.COMMITTED, processedChange.getStatusCode(),
            "Sync response should carry a COMMITTED ProcessedChange")
        assertTrue(processedChange.nrOfInserts > 0,
            "ProcessedChange should report at least one insert")

        // The inserted data should be immediately available in the KG (change is fully applied)
        val result = knowledgeGraph.query(
            QueryRequest(
                TestConstants.CONTEXT,
                "alice",
                podUri,
                query = "{ ex_Person { id so_givenName so_familyName } }"
            )
        ).toUni().await().indefinitely()
        val persons = result.data?.get("ex_Person") as List<Map<String, Any>>
        assertTrue(persons.isNotEmpty(), "Inserted person should be retrievable from the KG immediately after sync POST")

        // Clean up
        val delete = ChangeRequestInput(delete = personData)
        testHelpers.requestChangeViaHTTPSync(JsonLdHelper.encode(delete), podUri)
    }

    @Test
    @TestSecurity(user = "alice")
    fun testSyncModeReturnsNonCommittedStatusOnAssertionFailure() {
        // Insert a person first (async is fine here)
        val personData = TestDataGenerator.generatePersonData(1)
        val personId = personData.first()[JsonLdKeywords.id]!!
        testHelpers.requestChangeViaHTTPSync(JsonLdHelper.encode(ChangeRequestInput(insert = personData)), podUri)

        // Now post a change with an assertion that will fail (person already exists)
        val conflictingInsert = ChangeRequestInput(
            context = TestConstants.CONTEXT,
            assert = listOf(
                Assertion(
                    KvasirVocab.AssertEmptyResult,
                    "{ ex_Person(id: \"$personId\") { id } }"
                )
            ),
            insert = personData
        )

        // sync=true will return 400, with ASSERTION_FAILED in the body
        val responseBody = given()
            .contentType(RDFMediaTypes.JSON_LD)
            .accept(RDFMediaTypes.JSON_LD)
            .body(JsonLdHelper.encode(conflictingInsert))
            .queryParam("sync", "true")
            .post("$podUri/changes")
            .then()
            .statusCode(400)
            .extract().body().asString()

        val processedChange = JsonLdHelper.decode(responseBody, ProcessedChange::class.java)
        assertEquals(ChangeStatusCode.ASSERTION_FAILED, processedChange.getStatusCode(),
            "Sync response should carry an ASSERTION_FAILED ProcessedChange when the pre-condition is not met")

        // Clean up
        val delete = ChangeRequestInput(delete = personData)
        testHelpers.requestChangeViaHTTPSync(JsonLdHelper.encode(delete), podUri)
    }

    private fun getExpectedChangeRecords(changeRequestInput: ChangeRequestInput): Set<Pair<ChangeRecordType, RDFStatement>> {
        return (RDFTransformer.toStatements(changeRequestInput.insert.filterIsInstance<Map<String, Any>>(), podUri)
            .map { statement ->
                ChangeRecordType.INSERT to statement
            } + RDFTransformer.toStatements(
            changeRequestInput.delete.filterIsInstance<Map<String, Any>>()
        ).map { statement ->
            ChangeRecordType.DELETE to statement
        }).toSet()
    }

}
