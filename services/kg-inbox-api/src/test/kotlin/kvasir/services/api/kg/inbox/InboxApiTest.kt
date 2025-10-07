package kvasir.services.api.kg.inbox

import io.quarkus.test.common.http.TestHTTPEndpoint
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import kvasir.definitions.kg.*
import kvasir.definitions.kg.changes.Assertion
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.kg.slices.SliceStoreFactory
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.rdf.getJsonArray
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
    lateinit var sliceStoreFactory: SliceStoreFactory

    @Test
    @TestSecurity(user = "alice")
    fun testBasicInsertAndDelete() {
        val personData = TestDataGenerator.generatePersonData(1)
        val insert = ChangeRequestInput(insert = personData)

        // Perform change request for inserts
        val insertChangeRequestUri = testHelpers.requestChangeViaHTTPSync(insert, podUri)

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
        val deleteChangeRequestUri = testHelpers.requestChangeViaHTTPSync(delete, podUri)

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
        testHelpers.requestChangeViaHTTPSync(insert, podUri)

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
        testHelpers.requestChangeViaHTTPSync(badInsert, podUri, expectedResult = ChangeStatusCode.ASSERTION_FAILED)

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
        testHelpers.requestChangeViaHTTPSync(validDelete, podUri)

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
    fun testWithClause() {
        // Add a person
        val personData = TestDataGenerator.generatePersonData(1)
        val originalEmails = personData.first().getJsonArray<String>(SchemaVocab.email)!!.toSet()
        val personId = personData.first()[JsonLdKeywords.id]!!
        val personGivenName = personData.first()[SchemaVocab.givenName]!!
        val insert = ChangeRequestInput(insert = personData)
        val updatedEmail = "$personGivenName@somedomain.org"

        // Perform change request for inserts
        testHelpers.requestChangeViaHTTPSync(insert, podUri)

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

        val changeRequestUri = testHelpers.requestChangeViaHTTPSync(update, podUri)

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
        testHelpers.requestChangeViaHTTPSync(delete, podUri)
    }

    @Test
    @TestSecurity(user = "alice")
    fun testSliceInbox() {
        // Define Slice
        val sliceId = "$podUri/slices/test"
        sliceStoreFactory.getSliceStore(podUri).persist(
            Slice(
                sliceId,
                TestConstants.CONTEXT,
                "alice",
                "test",
                "",
                """
                    type Query {
                      ex_hello: String
                    }

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
                """.trimIndent(),
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
        testHelpers.requestChangeViaHTTPSync(invalidChange, podUri, ChangeStatusCode.VALIDATION_ERROR, sliceId)

        val invalidNonPersonData = listOf(
            mapOf(
                JsonLdKeywords.id to "ex:${UUID.randomUUID()}",
                JsonLdKeywords.type to "ex:Cat",
                SchemaVocab.givenName to "Mittens"
            )
        )
        val invalidChange2 = ChangeRequestInput(context = TestConstants.CONTEXT, insert = invalidNonPersonData)
        testHelpers.requestChangeViaHTTPSync(invalidChange2, podUri, ChangeStatusCode.VALIDATION_ERROR, sliceId)

        // Data that matches should be committed
        val validPersonData = TestDataGenerator.generatePersonData(1)
        val validChange = ChangeRequestInput(context = TestConstants.CONTEXT, insert = validPersonData)
        testHelpers.requestChangeViaHTTPSync(validChange, podUri, sliceUri = sliceId)

        // Delete the data
        val delete = ChangeRequestInput(context = TestConstants.CONTEXT, delete = validPersonData)
        testHelpers.requestChangeViaHTTPSync(delete, podUri, sliceUri = sliceId)
    }

    private fun getExpectedChangeRecords(changeRequestInput: ChangeRequestInput): Set<Pair<ChangeRecordType, RDFStatement>> {
        return (RDFTransformer.toStatements(changeRequestInput.insert.filterIsInstance<Map<String, Any>>())
            .map { statement ->
                ChangeRecordType.INSERT to statement
            } + RDFTransformer.toStatements(
            changeRequestInput.delete.filterIsInstance<Map<String, Any>>()
        ).map { statement ->
            ChangeRecordType.DELETE to statement
        }).toSet()
    }

}
