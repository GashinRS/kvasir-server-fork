package kvasir.services.api.kg.query

import com.github.jsonldjava.utils.JsonUtils
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.http.TestHTTPEndpoint
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.kg.ChangeRequest
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.definitions.kg.QueryResult
import kvasir.definitions.kg.graphql.*
import kvasir.definitions.rdf.*
import kvasir.utils.idgen.ChangeRequestId
import kvasir.utils.test.clickhouse.ClickhouseTestResource
import kvasir.utils.test.commons.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@QuarkusTest
@TestHTTPEndpoint(QueryApi::class)
@QuarkusTestResource(ClickhouseTestResource::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryApiTest {

    @Inject
    lateinit var kg: KnowledgeGraph

    @Inject
    lateinit var testHelpers: TestHelpers


    lateinit var podUri: String
    lateinit var personData: List<Map<String, Any>>

    @BeforeAll
    fun populateData() {
        podUri = testHelpers.getPodUri(TestConstants.TEST_POD_1_ID)
        personData = TestDataGenerator.generatePersonData(100)
        kg.process(
            ChangeRequest(
                ChangeRequestId.generate("$podUri/changes").encode(),
                emptyMap(),
                podUri,
                insert = personData
            )
        ).await().indefinitely()
    }

    @Test
    fun testGetPerson() {
        val selectedPerson = personData.random()
        val selectedPersonId = selectedPerson[JsonLdKeywords.id]!!
        val query = QueryInputWithContext(
            query = "{ ex_Person(id: \"$selectedPersonId\") { id so_givenName so_familyName so_email } }",
            providedContext = TestConstants.CONTEXT
        )


        // Perform query
        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(query)
            .post("{podId}$QUERY_API_PATH", TestConstants.TEST_POD_1_ID)
            .then()
            .statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        val retrievedPerson = (result.data?.get("ex_Person") as List<Map<String, Any>>).first()
        assertEquals(selectedPerson[JsonLdKeywords.id], retrievedPerson[FIELD_ID_NAME])
        assertEquals(listOf(selectedPerson[SchemaVocab.givenName]), retrievedPerson["so_givenName"])
        assertEquals(listOf(selectedPerson[SchemaVocab.familyName]), retrievedPerson["so_familyName"])
        assertEquals(
            (selectedPerson[SchemaVocab.email] as List<String>).toSet(),
            (retrievedPerson["so_email"] as List<String>).toSet()
        )
    }

    @Test
    fun testGetPersonByName() {
        val selectedPerson = personData.random()
        val selectedPersonGivenName = selectedPerson[SchemaVocab.givenName]!!
        val selectedPersonFamilyName = selectedPerson[SchemaVocab.familyName]!!
        val query = QueryInputWithContext(
            query = "{ ex_Person(so_givenName: \"$selectedPersonGivenName\", so_familyName: \"$selectedPersonFamilyName\") { id so_email } }",
            providedContext = TestConstants.CONTEXT
        )


        // Perform query
        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(query)
            .post("{podId}$QUERY_API_PATH", TestConstants.TEST_POD_1_ID)
            .then()
            .statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        val retrievedPerson = (result.data?.get("ex_Person") as List<Map<String, Any>>).first()
        assertEquals(selectedPerson[JsonLdKeywords.id], retrievedPerson[FIELD_ID_NAME])
        assertEquals(
            (selectedPerson[SchemaVocab.email] as List<String>).toSet(),
            (retrievedPerson["so_email"] as List<String>).toSet()
        )
    }

    @Test
    fun testGetPersons() {
        val query = QueryInputWithContext(
            query = "{ ex_Person { id so_givenName so_familyName so_email } }",
            providedContext = TestConstants.CONTEXT
        )

        // Perform query
        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(query)
            .post("{podId}$QUERY_API_PATH", TestConstants.TEST_POD_1_ID)
            .then()
            .statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        val expectedPersonMap = personData.associateBy { it[JsonLdKeywords.id]!! }
        val resultPersonMap =
            (result.data?.get("ex_Person") as List<Map<String, Any>>).associateBy { it[FIELD_ID_NAME]!! }
        expectedPersonMap.forEach { (id, expectedPerson) ->
            val retrievedPerson = resultPersonMap[id]!!
            assertEquals(listOf(expectedPerson[SchemaVocab.givenName]), retrievedPerson["so_givenName"])
            assertEquals(listOf(expectedPerson[SchemaVocab.familyName]), retrievedPerson["so_familyName"])
            assertEquals(
                (expectedPerson[SchemaVocab.email] as List<String>).toSet(),
                (retrievedPerson["so_email"] as List<String>).toSet()
            )
        }
    }

    @Test
    fun testGetPersonsJsonLD() {
        val query = QueryInputWithContext(
            query = "{ ex_Person { id so_givenName so_familyName so_email } }",
            providedContext = TestConstants.CONTEXT
        )

        // Perform query
        val result = JsonUtils.fromString(
            given()
                .accept(RDFMediaTypes.JSON_LD)
                .contentType(MediaType.APPLICATION_JSON)
                .body(query)
                .post("{podId}$QUERY_API_PATH", TestConstants.TEST_POD_1_ID)
                .then()
                .statusCode(200)
                .extract().body().asString()
        ) as List<Map<String, Any>>

        val expectedPersonMap = personData.associateBy { it[JsonLdKeywords.id]!! }
        val dataGraph = result.find { it[JsonLdKeywords.id] == KvasirNamedGraphs.queryResultDataGraph }!!
            .let { it[JsonLdKeywords.graph] as List<Map<String, Any>> }.first()["ex:Person"] as List<Map<String, Any>>
        val resultDataMap = (JsonLdHelper.toCompactFQForm(
            mapOf(
                JsonLdKeywords.context to TestConstants.CONTEXT,
                JsonLdKeywords.graph to dataGraph
            )
        )[JsonLdKeywords.graph] as List<Map<String, Any>>).associateBy { it[JsonLdKeywords.id]!! }
        expectedPersonMap.forEach { (id, expectedPerson) ->
            val retrievedPerson = resultDataMap[id]!!
            assertEquals(expectedPerson[SchemaVocab.givenName], retrievedPerson[SchemaVocab.givenName])
            assertEquals(expectedPerson[SchemaVocab.familyName], retrievedPerson[SchemaVocab.familyName])
            assertEquals(
                (expectedPerson[SchemaVocab.email] as List<String>).toSet(),
                (retrievedPerson[SchemaVocab.email] as List<String>).toSet()
            )
        }
    }

    @Test
    fun testFilter() {
        val startLetter = personData.random().let { it[SchemaVocab.givenName].toString().first() }
        // Subset of persons whose name starts with a specific letter
        val expectedPersons = personData.filter { it[SchemaVocab.givenName].toString().startsWith(startLetter) }

        val q = """
            {
              ex_Person {
                id
                so_givenName @filter(if: "it==$startLetter*")
              }
            }
        """.trimIndent()

        // Perform query
        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputWithContext(query = q, providedContext = TestConstants.CONTEXT))
            .post("{podId}$QUERY_API_PATH", TestConstants.TEST_POD_1_ID)
            .then()
            .statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        assertEquals(
            expectedPersons.map { it[JsonLdKeywords.id]!! }.toSet(),
            result.getDataField<List<Map<String, Any>>>("ex_Person")?.map { it[FIELD_ID_NAME] }?.toSet()
        )
    }

    @Test
    fun testPaginationAndSorting() {
        // Expect persons ordered by familyName and then by id
        val expectedResults =
            personData.sortedWith(Comparator.comparing<Map<String, Any>, String> { it[SchemaVocab.familyName].toString() }
                .thenComparing { entry -> entry[JsonLdKeywords.id].toString() })

        var cursor: String? = null
        val collectedPersons = mutableListOf<Map<String, Any>>()
        do {
            val result = given()
                .contentType(MediaType.APPLICATION_JSON)
                .body(getPaginationAndSortingQuery(cursor))
                .post("{podId}$QUERY_API_PATH", TestConstants.TEST_POD_1_ID)
                .then()
                .statusCode(200)
                .extract().body().`as`(QueryResult::class.java)
            collectedPersons.addAll(result.getDataField<List<Map<String, Any>>>("ex_Person")!!)
            cursor = result.extensions?.get("pagination")?.let {
                it as List<Map<String, Any>>
                it.first()["next"] as String?
            }
        } while (cursor != null)

        assertEquals(expectedResults.map { it[JsonLdKeywords.id] }, collectedPersons.map { it[FIELD_ID_NAME] })
    }

    private fun getPaginationAndSortingQuery(cursor: String? = null): QueryInputWithContext {
        return QueryInputWithContext(
            query = """
            {
              ex_Person(orderBy: ["so_familyName", "id"], pageSize: 10${cursor?.let { ", cursor: \"$it\"" } ?: ""}) {
                id
                so_givenName
                so_familyName
              }
            }
        """.trimIndent(), providedContext = TestConstants.CONTEXT
        )
    }

    @Test
    fun testTravelInverse() {
        val parentPerson = personData.random()
        val children = personData.shuffled().filter { it[JsonLdKeywords.id] != parentPerson[JsonLdKeywords.id] }.take(3)

        // Add parent relation to parentPerson for the selected children
        kg.process(
            ChangeRequest(
                ChangeRequestId.generate("$podUri/changes").encode(),
                emptyMap(),
                podUri,
                // Include type info for both sides of the relation to help the metadata generator
                insert = children.map {
                    mapOf(
                        JsonLdKeywords.id to it[JsonLdKeywords.id],
                        JsonLdKeywords.type to ExampleVocab.Person,
                        ExampleVocab.parent to mapOf(
                            JsonLdKeywords.id to parentPerson[JsonLdKeywords.id],
                            JsonLdKeywords.type to ExampleVocab.Person
                        )
                    )
                }
            )).await().indefinitely()

        // Query a specific child and travel non-inverse to the parent
        val selectedChild = children.random()
        var result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                QueryInputWithContext(
                    query = """
                        {
                          ex_Person(id: "${selectedChild[JsonLdKeywords.id]}") {
                            id
                            so_givenName
                            ex_parent {
                              id
                              so_givenName
                            }
                          }
                        }
                    """.trimIndent(), providedContext = TestConstants.CONTEXT
                )
            )
            .post("{podId}$QUERY_API_PATH", TestConstants.TEST_POD_1_ID)
            .then()
            .statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        // Validate the result
        val resultPerson = result.getDataField<List<Map<String, Any>>>("ex_Person")!!.first()
        val resultPersonParent = (resultPerson["ex_parent"] as List<Map<String, Any>>).first()
        assertEquals(listOf(selectedChild[SchemaVocab.givenName]), resultPerson["so_givenName"])
        assertEquals(parentPerson[JsonLdKeywords.id], resultPersonParent[FIELD_ID_NAME])
        assertEquals(listOf(parentPerson[SchemaVocab.givenName]), resultPersonParent["so_givenName"])

        // Now get the children for the parentPerson, using the reverse relation 'children'
        result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                QueryInputWithContext(
                    query = """
                        {
                          ex_Person(id: "${parentPerson[JsonLdKeywords.id]}") {
                            id
                            children {
                              id
                            }
                          }
                        }
                    """.trimIndent(), providedContext = TestConstants.CONTEXT
                )
            )
            .post("{podId}$QUERY_API_PATH", TestConstants.TEST_POD_1_ID)
            .then()
            .statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        // Validate the result
        var retrievedChildIds =
            (result.getDataField<List<Map<String, Any>>>("ex_Person")!!.first()["children"] as List<Map<String, Any>>)
        assertEquals(
            children.map { it[JsonLdKeywords.id] }.toSet(),
            retrievedChildIds.map { it[FIELD_ID_NAME] }.toSet()
        )

        // Repeat the previous query, but with a context where the reverse relation is defined using a prefix.
        result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                QueryInputWithContext(
                    query = """
                        {
                          ex_Person(id: "${parentPerson[JsonLdKeywords.id]}") {
                            id
                            children {
                              id
                            }
                          }
                        }
                    """.trimIndent(), providedContext = mapOf(
                        "ex" to "http://example.org/",
                        "so" to "http://schema.org/",
                        "children" to mapOf(JsonLdKeywords.reverse to "ex:parent")
                    )
                )
            )
            .post("{podId}$QUERY_API_PATH", TestConstants.TEST_POD_1_ID)
            .then()
            .statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        // Validate the result (again)
        retrievedChildIds =
            (result.getDataField<List<Map<String, Any>>>("ex_Person")!!.first()["children"] as List<Map<String, Any>>)
        assertEquals(
            children.map { it[JsonLdKeywords.id] }.toSet(),
            retrievedChildIds.map { it[FIELD_ID_NAME] }.toSet()
        )
    }

    @Test
    fun testSystemFields() {
        // Use Resource entrypoint to find all resources and their associated types
        var q = QueryInputWithContext(
            """
            {
              Resource {
                id
                _types
              }
            }
        """.trimIndent(), providedContext = TestConstants.CONTEXT
        )

        var result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(q)
            .post("{podId}$QUERY_API_PATH", TestConstants.TEST_POD_1_ID)
            .then()
            .statusCode(200)
            .extract().body().`as`(QueryResult::class.java)
        var returnedResources = result.getDataField<List<Map<String, Any>>>("Resource")!!
        assertEquals(
            personData.map { it[JsonLdKeywords.id] }.toSet(),
            returnedResources.map { it[FIELD_ID_NAME] }.toSet()
        )
        assertTrue(returnedResources.all { it[FIELD_TYPES_NAME] == listOf(ExampleVocab.Person) })

        // Use dynamic fields such as _relations, _predicates and _object without referencing a specific type
        val selectedPerson = personData.random()
        q = q.copy(
            query = """
            {
              Resource(id: "${selectedPerson[JsonLdKeywords.id]}") {
                id
                _relations(id: "${ExampleVocab.Person}")
                _predicates
                _object(predicate: "${SchemaVocab.givenName}") {
                    _rawRDF
                }
              }
            }
        """.trimIndent()
        )

        result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(q)
            .post("{podId}$QUERY_API_PATH", TestConstants.TEST_POD_1_ID)
            .then()
            .statusCode(200)
            .extract().body().`as`(QueryResult::class.java)
        var returnedResource = result.getDataField<List<Map<String, Any>>>("Resource")!!.first()
        assertEquals(selectedPerson[JsonLdKeywords.id], returnedResource[FIELD_ID_NAME])
        assertEquals(listOf(RDFVocab.type), returnedResource[FIELD_RELATIONS_NAME])
        assertEquals(
            setOf(RDFVocab.type, SchemaVocab.givenName, SchemaVocab.familyName, SchemaVocab.email),
            (returnedResource[FIELD_PREDICATES_NAME] as List<Any>).toSet()
        )
        assertEquals(
            mapOf(
                JsonLdKeywords.value to selectedPerson[SchemaVocab.givenName],
                JsonLdKeywords.type to XSDVocab.string
            ),
            (returnedResource[FIELD_OBJECT_NAME] as List<Map<String, Any>>).first()[FIELD_RAW_RDF_NAME]
        )

        // Use an inline Fragment to access a Person via Resource
        q = q.copy(
            """
            {
              Resource(id: "${selectedPerson[JsonLdKeywords.id]}") {
                id
                ... on ex_Person {
                  so_givenName
                }
              }
            }
        """.trimIndent()
        )
        result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(q)
            .post("{podId}$QUERY_API_PATH", TestConstants.TEST_POD_1_ID)
            .then()
            .statusCode(200)
            .extract().body().`as`(QueryResult::class.java)
        returnedResource = result.getDataField<List<Map<String, Any>>>("Resource")!!.first()
        assertEquals(selectedPerson[JsonLdKeywords.id], returnedResource[FIELD_ID_NAME])
        assertEquals(listOf(selectedPerson[SchemaVocab.givenName]), returnedResource["so_givenName"])
    }

    @Test
    fun testRawRDFField() {
        // Add a new relation to a Person, which can refer both to another Person or literal values
        val selectedPerson = personData.random()
        val selectedPersonId = selectedPerson[JsonLdKeywords.id]!!
        val targetPersons =
            personData.filter { it[JsonLdKeywords.id] != selectedPersonId }.shuffled().take(2)
        val knowsLiteralNames = listOf("Henry", "Mary")
        val targetPersonsLd = targetPersons.map {
            mapOf(
                JsonLdKeywords.id to it[JsonLdKeywords.id],
                JsonLdKeywords.type to ExampleVocab.Person
            )
        }
        val changeRequest = ChangeRequest(
            ChangeRequestId.generate("$podUri/changes").encode(),
            emptyMap(),
            podUri,
            // Include type info for both sides of the relation to help the metadata generator
            insert = listOf(
                mapOf(
                    JsonLdKeywords.id to selectedPerson[JsonLdKeywords.id],
                    JsonLdKeywords.type to ExampleVocab.Person,
                    ExampleVocab.knows to targetPersonsLd
                )
            )
        )
        kg.process(changeRequest).await().indefinitely()

        val q = QueryInputWithContext(
            """
            {
              ex_Person {
                id
                _rawRDF
                ex_knows {
                  _rawRDF
                }
              }
            }
        """.trimIndent(), providedContext = TestConstants.CONTEXT
        )
        var result = testHelpers.queryKGViaHTTP(q, podUri)
        var persons = result.getDataField<List<Map<String, Any>>>("ex_Person")!!

        // There should only be one result (as other instances don't have the knows relation)
        assertEquals(1, persons.size)
        assertEquals(selectedPersonId, persons.first()[FIELD_ID_NAME])
        assertEquals(selectedPersonId, persons.first().getJsonObject(FIELD_RAW_RDF_NAME)?.get(JsonLdKeywords.id))
        assertEquals(
            targetPersons.map { it[JsonLdKeywords.id] }.toSet(),
            persons.first().getJsonArray<JSONObject>("ex_knows")
                ?.map { it.getJsonObject(FIELD_RAW_RDF_NAME)?.get(JsonLdKeywords.id) }
                ?.toSet()
        )

        // Now add literal values as object for the knows relation, making the return type of the field an RDFNode
        val changeRequest2 = ChangeRequest(
            ChangeRequestId.generate("$podUri/changes").encode(),
            emptyMap(),
            podUri,
            insert = listOf(
                mapOf(
                    JsonLdKeywords.id to selectedPerson[JsonLdKeywords.id],
                    JsonLdKeywords.type to ExampleVocab.Person,
                    ExampleVocab.knows to knowsLiteralNames
                )
            )
        )
        kg.process(changeRequest2).await().indefinitely()

        // Perform the query again, rawRDF should contain both Person ids and the literal values.
        result = testHelpers.queryKGViaHTTP(q, podUri)
        persons = result.getDataField<List<Map<String, Any>>>("ex_Person")!!
        assertEquals(
            targetPersons.map { it[JsonLdKeywords.id] }.toSet(),
            persons.first().getJsonArray<JSONObject>("ex_knows")
                ?.mapNotNull { it.getJsonObject(FIELD_RAW_RDF_NAME)?.get(JsonLdKeywords.id) }
                ?.toSet()
        )
        assertEquals(
            knowsLiteralNames.toSet(),
            persons.first().getJsonArray<JSONObject>("ex_knows")
                ?.mapNotNull { it.getJsonObject(FIELD_RAW_RDF_NAME)?.get(JsonLdKeywords.value) }?.toSet()
        )

        val reverseChanges = ChangeRequest(
            ChangeRequestId.generate("$podUri/changes").encode(),
            emptyMap(),
            podUri,
            delete = listOf(
                mapOf(
                    JsonLdKeywords.id to selectedPerson[JsonLdKeywords.id],
                    JsonLdKeywords.type to ExampleVocab.Person,
                    ExampleVocab.knows to targetPersonsLd + knowsLiteralNames
                )
            )
        )
        kg.process(reverseChanges).await().indefinitely()

        // Perform the query again, there should be no results
        result = testHelpers.queryKGViaHTTP(q, podUri)
        persons = result.getDataField<List<Map<String, Any>>>("ex_Person")!!
        assertEquals(0, persons.size)
    }

}