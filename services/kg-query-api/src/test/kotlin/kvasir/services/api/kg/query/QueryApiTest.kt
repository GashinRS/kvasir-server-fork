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
import kvasir.definitions.kg.graphql.FIELD_ID_NAME
import kvasir.definitions.rdf.*
import kvasir.utils.idgen.ChangeRequestId
import kvasir.utils.test.clickhouse.ClickhouseTestResource
import kvasir.utils.test.commons.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.Comparator

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
    @Order(7)
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

}