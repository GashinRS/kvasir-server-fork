package kvasir.plugins.kg.xtdb

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import kvasir.definitions.graphql.GraphQLUtils
import kvasir.definitions.kg.ChangeRequest
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.kg.changeops.Assertion
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import kvasir.utils.json.transform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.*


@QuarkusTest
class XtdbKGMutationTest {

    @Inject
    lateinit var kg: XtdbKnowledgeGraph

    @Test
    fun testInsert() {
        val testId = UUID.randomUUID().toString()
        val insert = ChangeRequest(
            context = testContext,
            podId = testId,
            insert = NamedResourceUtils.toJSONLD(RESOURCE_ALICE_BASIC)
        )

        // Store the data
        kg.process(insert).await().indefinitely()

        // Retrieve the data
        val result =
            kg.query(
                QueryRequest(
                    podId = testId,
                    graphQL = GraphQLUtils.parseDocumentWithContext(QUERY_ALICE_BASIC, testContext)
                )
            )
                .await().indefinitely()

        // Check the result
        assertEquals(RESOURCE_ALICE_BASIC.givenName, result.data.transform("ex_Person.so_givenName[0]"))
        assertEquals(RESOURCE_ALICE_BASIC.familyName, result.data.transform("ex_Person.so_familyName[0]"))
    }

    @Test
    fun testDelete() {
        val testId = UUID.randomUUID().toString()
        val insert = ChangeRequest(
            context = testContext,
            podId = testId,
            insert = NamedResourceUtils.toJSONLD(RESOURCE_ALICE_BASIC)
        )

        // Store the data
        kg.process(insert).await().indefinitely()

        // Delete the data
        val delete = ChangeRequest(
            context = testContext,
            podId = testId,
            delete = NamedResourceUtils.toJSONLD(RESOURCE_ALICE_BASIC)
        )
        kg.process(delete).await().indefinitely()

        // Retrieve the data
        val result =
            kg.query(
                QueryRequest(
                    podId = testId,
                    graphQL = GraphQLUtils.parseDocumentWithContext(QUERY_ALICE_BASIC, testContext)
                )
            )
                .await().indefinitely()

        // Check the result
        assertEquals(0, result.data.size)
    }

    @Test
    fun testAssertion() {
        val testId = UUID.randomUUID().toString()
        val insert = ChangeRequest(
            context = testContext,
            podId = testId,
            insert = NamedResourceUtils.toJSONLD(RESOURCE_ALICE_BASIC)
        )

        // Store the data
        kg.process(insert).await().indefinitely()

        // The additional insert may only proceed if there is no data for ex:alice
        val assert = ChangeRequest(
            context = testContext,
            podId = testId,
            assert = listOf(
                Assertion(
                    type = KvasirVocab.AssertEmptyResult,
                    queryStr = """
                        query {
                            ex_Person(id: "ex:alice") {
                                so_givenName
                                so_familyName
                            }
                        }
                    """
                )
            ),
            insert = listOf(
                mapOf(
                    JsonLdKeywords.context to testContext,
                    JsonLdKeywords.id to "ex:alice",
                    "so:email" to "alice@example.org"
                )
            )
        )
        kg.process(assert).await().indefinitely()

        // Email should not have been inserted
        val result =
            kg.query(
                QueryRequest(
                    podId = testId,
                    graphQL = GraphQLUtils.parseDocumentWithContext(
                        "{ ex_Person(id: \"ex:alice\") { so_email } }",
                        testContext
                    )
                )
            )
                .await().indefinitely()
        assertEquals(0, result.data.size)
    }

    @Test
    fun testUpdateViaWith() {
        val testId = UUID.randomUUID().toString()
        val insert = ChangeRequest(
            context = testContext,
            podId = testId,
            insert = NamedResourceUtils.toJSONLD(RESOURCE_ALICE_BASIC)
        )

        // Store the data
        kg.process(insert).await().indefinitely()

        // Add email address for Alice by first finding the id for a Person with givenName "Alice"
        val update = ChangeRequest(
            context = testContext,
            podId = testId,
            with = "{ ex_Person(so_givenName: \"Alice\") { id } }",
            insert = listOf(
                "{ \"@id\": ex_Person.id, \"so:email\": \"alice@example.org\" }"
            )
        )
        kg.process(update).await().indefinitely()

        // Retrieve the data
        val result =
            kg.query(
                QueryRequest(
                    podId = testId,
                    graphQL = GraphQLUtils.parseDocumentWithContext(
                        "{ ex_Person(id: \"ex:alice\") { so_email } }",
                        testContext
                    )
                )
            )
                .await().indefinitely()

        // Check the result
        assertEquals("alice@example.org", result.data.transform("ex_Person.so_email[0]"))
    }
}