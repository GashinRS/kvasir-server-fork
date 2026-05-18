package kvasir.services.api.kg.query

import com.github.jsonldjava.utils.JsonUtils
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
import kvasir.definitions.kg.graphql.*
import kvasir.definitions.rdf.*
import kvasir.utils.idgen.ChangeRequestId
import kvasir.utils.idgen.StateId
import kvasir.utils.test.commons.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@QuarkusTest
@TestHTTPEndpoint(QueryApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryApiTest : AbstractPodTest() {

    @Inject
    lateinit var kg: KnowledgeGraph

    lateinit var personData: List<Map<String, Any>>

    @BeforeAll
    fun populateData() {
        personData = TestDataGenerator.generatePersonData(100)
        kg.process(
            ChangeRequest(
                id = ChangeRequestId.generate("$podUri/changes").encode(),
                changeId = StateId.generate(),
                context = emptyMap(),
                requestingUser = "alice",
                podId = podUri,
                insert = personData
            )
        ).chain { processedChange ->
            // Manually finalize the request
            kg.finalize(ChangeFinalizeRequest(podUri, processedChange.id))
        }.await().indefinitely()

    }

    @Test
    @TestSecurity(user = "alice")
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
            .post("{podId}$QUERY_API_PATH", podName)
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
    @TestSecurity(user = "alice")
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
            .post("{podId}$QUERY_API_PATH", podName)
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
    @TestSecurity(user = "alice")
    fun testGetPersons() {
        val query = QueryInputWithContext(
            query = "{ ex_Person { id so_givenName so_familyName so_email } }",
            providedContext = TestConstants.CONTEXT
        )

        // Perform query
        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(query)
            .post("{podId}$QUERY_API_PATH", podName)
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
    @TestSecurity(user = "alice")
    fun testGetPersonsJsonLD() {
        val query = QueryInputWithContext(
            query = "{ ex_Person { id so_givenName so_familyName so_email } }",
            providedContext = TestConstants.CONTEXT
        )

        // Perform query
        val rawResult =
            given()
                .accept(RDFMediaTypes.JSON_LD)
                .contentType(MediaType.APPLICATION_JSON)
                .body(query)
                .post("{podId}$QUERY_API_PATH", podName)
                .then()
                .statusCode(200)
                .extract().body().asString()
        val result = JsonUtils.fromString(rawResult) as List<Map<String, Any>>

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
    @TestSecurity(user = "alice")
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
            .post("{podId}$QUERY_API_PATH", podName)
            .then()
            .statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        assertEquals(
            expectedPersons.map { it[JsonLdKeywords.id]!! }.toSet(),
            result.getDataField<List<Map<String, Any>>>("ex_Person")?.map { it[FIELD_ID_NAME] }?.toSet()
        )
    }

    @Test
    @TestSecurity(user = "alice")
    fun testFilterAtDifferentLevels() {
        val startLetter = personData.random().let { it[SchemaVocab.givenName].toString().first() }
        // Subset of persons whose given name starts with the selected letter
        val expectedPersonIds = personData
            .filter { it[SchemaVocab.givenName].toString().startsWith(startLetter) }
            .map { it[JsonLdKeywords.id]!! }
            .toSet()

        // 1. Filter at type level using fully-qualified field name in the expression
        val typeLevel = QueryInputWithContext(
            query = """
                {
                  ex_Person @filter(if: "so_givenName==$startLetter*") {
                    id
                    so_givenName
                  }
                }
            """.trimIndent(),
            providedContext = TestConstants.CONTEXT
        )
        val typeLevelResult = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(typeLevel)
            .post("{podId}$QUERY_API_PATH", podName)
            .then()
            .statusCode(200)
            .extract().body().`as`(QueryResult::class.java)
        assertEquals(
            expectedPersonIds,
            typeLevelResult.getDataField<List<Map<String, Any>>>("ex_Person")?.map { it[FIELD_ID_NAME] }?.toSet()
        )

        // 2. Filter at field level referencing the field name explicitly
        val fieldLevelFieldName = QueryInputWithContext(
            query = """
                {
                  ex_Person {
                    id
                    so_givenName @filter(if: "so_givenName==$startLetter*")
                  }
                }
            """.trimIndent(),
            providedContext = TestConstants.CONTEXT
        )
        val fieldLevelFieldNameResult = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(fieldLevelFieldName)
            .post("{podId}$QUERY_API_PATH", podName)
            .then()
            .statusCode(200)
            .extract().body().`as`(QueryResult::class.java)
        assertEquals(
            expectedPersonIds,
            fieldLevelFieldNameResult.getDataField<List<Map<String, Any>>>("ex_Person")?.map { it[FIELD_ID_NAME] }?.toSet()
        )

        // 3. Filter at field level using 'it' shorthand
        val fieldLevelIt = QueryInputWithContext(
            query = """
                {
                  ex_Person {
                    id
                    so_givenName @filter(if: "it==$startLetter*")
                  }
                }
            """.trimIndent(),
            providedContext = TestConstants.CONTEXT
        )
        val fieldLevelItResult = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(fieldLevelIt)
            .post("{podId}$QUERY_API_PATH", podName)
            .then()
            .statusCode(200)
            .extract().body().`as`(QueryResult::class.java)
        assertEquals(
            expectedPersonIds,
            fieldLevelItResult.getDataField<List<Map<String, Any>>>("ex_Person")?.map { it[FIELD_ID_NAME] }?.toSet()
        )
    }

    @Test
    @TestSecurity(user = "alice")
    fun testOptionalScalarField() {
        // Case 1: optional on a scalar (String) field — ex:email set only for a subset of persons.
        val personsWithEmail = personData.shuffled().take(personData.size / 2)
        val personsWithEmailIds = personsWithEmail.map { it[JsonLdKeywords.id]!! }.toSet()
        val personsWithoutEmailIds = personData.map { it[JsonLdKeywords.id]!! }.toSet() - personsWithEmailIds

        // Insert ex:email only for the selected subset
        kg.process(
            ChangeRequest(
                id = ChangeRequestId.generate("$podUri/changes").encode(),
                changeId = StateId.generate(),
                context = emptyMap(),
                requestingUser = "alice",
                podId = podUri,
                insert = personsWithEmail.map {
                    mapOf(
                        JsonLdKeywords.id to it[JsonLdKeywords.id],
                        JsonLdKeywords.type to ExampleVocab.Person,
                        ExampleVocab.email to "contact@example.org"
                    )
                }
            )
        ).chain { processedChange ->
            kg.finalize(ChangeFinalizeRequest(podUri, processedChange.id))
        }.await().indefinitely()

        // Without @optional: only persons WITH ex:email are returned (inner-join semantics)
        val withoutOptional = QueryInputWithContext(
            query = """
                {
                  ex_Person {
                    id
                    ex_email
                  }
                }
            """.trimIndent(),
            providedContext = TestConstants.CONTEXT
        )
        val withoutOptionalResult = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(withoutOptional)
            .post("{podId}$QUERY_API_PATH", podName)
            .then()
            .statusCode(200)
            .extract().body().`as`(QueryResult::class.java)
        val returnedWithout = withoutOptionalResult.getDataField<List<Map<String, Any>>>("ex_Person")!!
        assertEquals(
            personsWithEmailIds,
            returnedWithout.map { it[FIELD_ID_NAME]!! }.toSet(),
            "Without @optional only persons with ex_email should be returned"
        )

        // With @optional: ALL persons are returned; those without ex:email have a null/empty value (left-join semantics)
        val withOptional = QueryInputWithContext(
            query = """
                {
                  ex_Person {
                    id
                    ex_email @optional
                  }
                }
            """.trimIndent(),
            providedContext = TestConstants.CONTEXT
        )
        val withOptionalResult = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(withOptional)
            .post("{podId}$QUERY_API_PATH", podName)
            .then()
            .statusCode(200)
            .extract().body().`as`(QueryResult::class.java)
        val returnedWith = withOptionalResult.getDataField<List<Map<String, Any>>>("ex_Person")!!
        assertEquals(
            personData.map { it[JsonLdKeywords.id]!! }.toSet(),
            returnedWith.map { it[FIELD_ID_NAME]!! }.toSet(),
            "With @optional all persons should be returned"
        )
        val personsWithoutInResult = returnedWith.filter { it[FIELD_ID_NAME]!! in personsWithoutEmailIds }
        assertTrue(
            personsWithoutInResult.all { it["ex_email"] == null || (it["ex_email"] as? List<*>)?.all { item -> item == "" } == true },
            "Persons without ex_email should have a null or empty value when @optional is used"
        )
    }

    @Test
    @TestSecurity(user = "alice")
    fun testOptionalRelationField() {
        // Case 2: optional on a non-scalar relation field — ex:friendOf pointing to another Person,
        // set only for a subset of persons.
        val personsWithFriendOf = personData.shuffled().take(personData.size / 2)
        val personsWithFriendOfIds = personsWithFriendOf.map { it[JsonLdKeywords.id]!! }.toSet()
        val personsWithoutFriendOfIds = personData.map { it[JsonLdKeywords.id]!! }.toSet() - personsWithFriendOfIds
        val targetPerson = personData.first { it[JsonLdKeywords.id] !in personsWithFriendOfIds }

        kg.process(
            ChangeRequest(
                id = ChangeRequestId.generate("$podUri/changes").encode(),
                changeId = StateId.generate(),
                context = emptyMap(),
                requestingUser = "alice",
                podId = podUri,
                insert = personsWithFriendOf.map {
                    mapOf(
                        JsonLdKeywords.id to it[JsonLdKeywords.id],
                        JsonLdKeywords.type to ExampleVocab.Person,
                        ExampleVocab.friendOf to mapOf(
                            JsonLdKeywords.id to targetPerson[JsonLdKeywords.id],
                            JsonLdKeywords.type to ExampleVocab.Person
                        )
                    )
                }
            )
        ).chain { processedChange ->
            kg.finalize(ChangeFinalizeRequest(podUri, processedChange.id))
        }.await().indefinitely()

        // Without @optional: only persons WITH ex:friendOf are returned
        val withoutOptional = QueryInputWithContext(
            query = """
                {
                  ex_Person {
                    id
                    ex_friendOf {
                      id
                    }
                  }
                }
            """.trimIndent(),
            providedContext = TestConstants.CONTEXT
        )
        val withoutOptionalResult = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(withoutOptional)
            .post("{podId}$QUERY_API_PATH", podName)
            .then()
            .statusCode(200)
            .extract().body().`as`(QueryResult::class.java)
        val returnedWithout = withoutOptionalResult.getDataField<List<Map<String, Any>>>("ex_Person")!!
        assertEquals(
            personsWithFriendOfIds,
            returnedWithout.map { it[FIELD_ID_NAME]!! }.toSet(),
            "Without @optional only persons with ex_friendOf should be returned"
        )

        // With @optional: ALL persons are returned; those without ex:friendOf have a null/empty relation
        val withOptional = QueryInputWithContext(
            query = """
                {
                  ex_Person {
                    id
                    ex_friendOf @optional {
                      id
                    }
                  }
                }
            """.trimIndent(),
            providedContext = TestConstants.CONTEXT
        )
        val withOptionalResult = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(withOptional)
            .post("{podId}$QUERY_API_PATH", podName)
            .then()
            .statusCode(200)
            .extract().body().`as`(QueryResult::class.java)
        val returnedWith = withOptionalResult.getDataField<List<Map<String, Any>>>("ex_Person")!!
        assertEquals(
            personData.map { it[JsonLdKeywords.id]!! }.toSet(),
            returnedWith.map { it[FIELD_ID_NAME]!! }.toSet(),
            "With @optional all persons should be returned"
        )
        val personsWithoutInResult = returnedWith.filter { it[FIELD_ID_NAME]!! in personsWithoutFriendOfIds }
        assertTrue(
            personsWithoutInResult.all { it["ex_friendOf"] == null || (it["ex_friendOf"] as? List<*>)?.isEmpty() == true },
            "Persons without ex_friendOf should have a null or empty relation when @optional is used"
        )
        // Persons WITH ex:friendOf should point to the target person
        val personsWithInResult = returnedWith.filter { it[FIELD_ID_NAME]!! in personsWithFriendOfIds }
        assertTrue(
            personsWithInResult.all {
                @Suppress("UNCHECKED_CAST")
                (it["ex_friendOf"] as? List<Map<String, Any>>)?.any { rel -> rel[FIELD_ID_NAME] == targetPerson[JsonLdKeywords.id] } == true
            },
            "Persons with ex_friendOf should point to the target person"
        )
    }

    @Test
    @TestSecurity(user = "alice")
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
                .post("{podId}$QUERY_API_PATH", podName)
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
    @TestSecurity(user = "alice")
    fun testTravelInverse() {
        val parentPerson = personData.random()
        val children = personData.shuffled().filter { it[JsonLdKeywords.id] != parentPerson[JsonLdKeywords.id] }.take(3)

        // Add parent relation to parentPerson for the selected children
        kg.process(
            ChangeRequest(
                id = ChangeRequestId.generate("$podUri/changes").encode(),
                changeId = StateId.generate(),
                context = emptyMap(),
                requestingUser = "alice",
                podId = podUri,
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
            )).chain { processedChange ->
            // Manually finalize the request
            kg.finalize(ChangeFinalizeRequest(podUri, processedChange.id))
        }.await().indefinitely()

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
            .post("{podId}$QUERY_API_PATH", podName)
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
            .post("{podId}$QUERY_API_PATH", podName)
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
            .post("{podId}$QUERY_API_PATH", podName)
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
    @TestSecurity(user = "alice")
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
            .post("{podId}$QUERY_API_PATH", podName)
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
            .post("{podId}$QUERY_API_PATH", podName)
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
            .post("{podId}$QUERY_API_PATH", podName)
            .then()
            .statusCode(200)
            .extract().body().`as`(QueryResult::class.java)
        returnedResource = result.getDataField<List<Map<String, Any>>>("Resource")!!.first()
        assertEquals(selectedPerson[JsonLdKeywords.id], returnedResource[FIELD_ID_NAME])
        assertEquals(listOf(selectedPerson[SchemaVocab.givenName]), returnedResource["so_givenName"])
    }

    @Test
    @TestSecurity(user = "alice")
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
            id = ChangeRequestId.generate("$podUri/changes").encode(),
            changeId = StateId.generate(),
            context = emptyMap(),
            requestingUser = "alice",
            podId = podUri,
            // Include type info for both sides of the relation to help the metadata generator
            insert = listOf(
                mapOf(
                    JsonLdKeywords.id to selectedPerson[JsonLdKeywords.id],
                    JsonLdKeywords.type to ExampleVocab.Person,
                    ExampleVocab.knows to targetPersonsLd
                )
            )
        )
        kg.process(changeRequest)
            .chain { processedChange ->
                // Manually finalize the request
                kg.finalize(ChangeFinalizeRequest(podUri, processedChange.id))
            }
            .await().indefinitely()

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
            id = ChangeRequestId.generate("$podUri/changes").encode(),
            changeId = StateId.generate(),
            context = emptyMap(),
            requestingUser = "alice",
            podId = podUri,
            insert = listOf(
                mapOf(
                    JsonLdKeywords.id to selectedPerson[JsonLdKeywords.id],
                    JsonLdKeywords.type to ExampleVocab.Person,
                    ExampleVocab.knows to knowsLiteralNames
                )
            )
        )
        kg.process(changeRequest2).chain { processedChange ->
            // Manually finalize the request
            kg.finalize(ChangeFinalizeRequest(podUri, processedChange.id))
        }.await().indefinitely()

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
            id = ChangeRequestId.generate("$podUri/changes").encode(),
            changeId = StateId.generate(),
            context = emptyMap(),
            requestingUser = "alice",
            podId = podUri,
            delete = listOf(
                mapOf(
                    JsonLdKeywords.id to selectedPerson[JsonLdKeywords.id],
                    JsonLdKeywords.type to ExampleVocab.Person,
                    ExampleVocab.knows to targetPersonsLd + knowsLiteralNames
                )
            )
        )
        kg.process(reverseChanges).chain { processedChange ->
            // Manually finalize the request
            kg.finalize(ChangeFinalizeRequest(podUri, processedChange.id))
        }.await().indefinitely()

        // Perform the query again, there should be no results
        result = testHelpers.queryKGViaHTTP(q, podUri)
        persons = result.getDataField<List<Map<String, Any>>>("ex_Person")!!
        assertEquals(0, persons.size)
    }

}