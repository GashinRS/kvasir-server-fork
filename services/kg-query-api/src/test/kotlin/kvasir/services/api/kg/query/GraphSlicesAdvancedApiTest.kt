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
import kvasir.definitions.kg.graphql.FIELD_RAW_RDF_NAME
import kvasir.definitions.kg.slices.EmbeddedSliceSchema
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.XSDVocab
import kvasir.utils.idgen.ChangeRequestId
import kvasir.utils.idgen.StateId
import kvasir.utils.test.commons.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tests for advanced Slice schema features:
 * - @mustExist: enforce type-level membership even when the guarded field is not selected
 * - RDFNode: fields whose value can be either a resource IRI or a literal (multiple types)
 */
@QuarkusTest
@TestHTTPEndpoint(GraphSlicesApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GraphSlicesAdvancedApiTest : AbstractPodTest() {

    @Inject
    lateinit var kg: KnowledgeGraph

    @Inject
    lateinit var repositoryFactory: RepositoryFactory

    // -----------------------------------------------------------------------
    // @hidden directive test data
    // -----------------------------------------------------------------------

    private val hiddenSliceName = "hidden-test"
    private lateinit var hiddenSliceUri: String

    private val hiddenQualifyingDomain = "@hidden-test.org"

    /** Persons whose email qualifies — visible through the Slice. */
    private lateinit var personsVisibleInHiddenSlice: List<Map<String, Any>>

    /** Persons with no qualifying email — invisible through the Slice. */
    private lateinit var personsHiddenInHiddenSlice: List<Map<String, Any>>

    // -----------------------------------------------------------------------
    // @mustExist test data
    // -----------------------------------------------------------------------

    private val mustExistSliceName = "must-exist-test"
    private lateinit var mustExistSliceUri: String

    /** Persons that carry the qualifying email domain — visible through the Slice. */
    private lateinit var personsWithQualifyingEmail: List<Map<String, Any>>

    /** Persons that do NOT have any matching email — invisible through the Slice. */
    private lateinit var personsWithoutQualifyingEmail: List<Map<String, Any>>

    private val qualifyingDomain = "@must-exist-test.org"

    // -----------------------------------------------------------------------
    // RDFNode / multi-type field test data
    // -----------------------------------------------------------------------

    private val rdfNodeSliceName = "rdf-node-test"
    private lateinit var rdfNodeSliceUri: String

    private val instrumentLiteralId = "${ExampleVocab.baseUri}instruments/literal-maker"
    private val instrumentIriId = "${ExampleVocab.baseUri}instruments/iri-maker"
    private val manufacturerLiteralValue = "Fender"
    private val manufacturerIriValue = "https://www.espguitars.com"

    // -----------------------------------------------------------------------
    // Setup
    // -----------------------------------------------------------------------

    @BeforeAll
    fun setupSlicesAndData() {
        setupHiddenSlice()
        setupMustExistSlice()
        setupRdfNodeSlice()
    }

    private fun setupHiddenSlice() {
        hiddenSliceUri = "$podUri/slices/$hiddenSliceName"

        // so_email is @hidden: it restricts the visible persons via @filter but is never
        // exposed to clients — neither via introspection nor as a selectable field.
        val schema = """
            type Query {
                persons: [ex_Person!]!
            }

            type ex_Person {
                id: ID!
                so_givenName: String!
                so_email: [String!] @hidden @filter(if: "it==*$hiddenQualifyingDomain")
            }
        """.trimIndent()

        repositoryFactory.getVersionedRepository(Slice::class, podUri).persist(
            Slice(
                id = hiddenSliceUri,
                name = hiddenSliceName,
                description = "",
                createdBy = "alice",
                context = TestConstants.CONTEXT,
                schema = EmbeddedSliceSchema(schema)
            )
        ).await().indefinitely()

        personsVisibleInHiddenSlice = TestDataGenerator.generatePersonData(5).map { person ->
            person.apply {
                val firstName = person[SchemaVocab.givenName] as String
                this[SchemaVocab.email] = listOf("$firstName$hiddenQualifyingDomain")
            }
        }
        personsHiddenInHiddenSlice = TestDataGenerator.generatePersonData(8)

        kg.process(
            ChangeRequest(
                id = ChangeRequestId.generate("$podUri/changes").encode(),
                changeId = StateId.generate(),
                context = emptyMap(),
                requestingUser = "alice",
                podId = podUri,
                insert = personsVisibleInHiddenSlice + personsHiddenInHiddenSlice
            )
        ).chain { processedChange ->
            kg.finalize(ChangeFinalizeRequest(podUri, processedChange.id))
        }.await().indefinitely()
    }

    private fun setupMustExistSlice() {
        mustExistSliceUri = "$podUri/slices/$mustExistSliceName"

        // The schema uses @mustExist together with @filter on so_email.
        // Only persons whose email ends with the qualifying domain are members of this Slice.
        val schema = """
            type Query {
                persons: [ex_Person!]!
            }

            type ex_Person {
                id: ID!
                so_givenName: String!
                so_email: [String!] @mustExist @filter(if: "it==*$qualifyingDomain")
            }
        """.trimIndent()

        repositoryFactory.getVersionedRepository(Slice::class, podUri).persist(
            Slice(
                id = mustExistSliceUri,
                name = mustExistSliceName,
                description = "",
                createdBy = "alice",
                context = TestConstants.CONTEXT,
                schema = EmbeddedSliceSchema(schema)
            )
        ).await().indefinitely()

        // Persons WITH the qualifying email (visible in Slice)
        personsWithQualifyingEmail = TestDataGenerator.generatePersonData(5).map { person ->
            person.apply {
                val firstName = person[SchemaVocab.givenName] as String
                this[SchemaVocab.email] = listOf("$firstName$qualifyingDomain")
            }
        }
        // Persons WITHOUT a qualifying email (invisible in Slice)
        personsWithoutQualifyingEmail = TestDataGenerator.generatePersonData(10)

        kg.process(
            ChangeRequest(
                id = ChangeRequestId.generate("$podUri/changes").encode(),
                changeId = StateId.generate(),
                context = emptyMap(),
                requestingUser = "alice",
                podId = podUri,
                insert = personsWithQualifyingEmail + personsWithoutQualifyingEmail
            )
        ).chain { processedChange ->
            kg.finalize(ChangeFinalizeRequest(podUri, processedChange.id))
        }.await().indefinitely()
    }

    private fun setupRdfNodeSlice() {
        rdfNodeSliceUri = "$podUri/slices/$rdfNodeSliceName"

        val schema = """
            type Query {
                instruments: [ex_Instrument!]!
            }

            type ex_Instrument {
                id: ID!
                ex_manufacturer: [RDFNode]
            }
        """.trimIndent()

        repositoryFactory.getVersionedRepository(Slice::class, podUri).persist(
            Slice(
                id = rdfNodeSliceUri,
                name = rdfNodeSliceName,
                description = "",
                createdBy = "alice",
                context = TestConstants.CONTEXT,
                schema = EmbeddedSliceSchema(schema)
            )
        ).await().indefinitely()

        // Insert two instruments: one with a literal manufacturer, one with an IRI manufacturer
        kg.process(
            ChangeRequest(
                id = ChangeRequestId.generate("$podUri/changes").encode(),
                changeId = StateId.generate(),
                context = emptyMap(),
                requestingUser = "alice",
                podId = podUri,
                insert = listOf(
                    mapOf(
                        JsonLdKeywords.id to instrumentLiteralId,
                        JsonLdKeywords.type to "${ExampleVocab.baseUri}Instrument",
                        // literal String value
                        "${ExampleVocab.baseUri}manufacturer" to manufacturerLiteralValue
                    ),
                    mapOf(
                        JsonLdKeywords.id to instrumentIriId,
                        JsonLdKeywords.type to "${ExampleVocab.baseUri}Instrument",
                        // IRI (resource reference)
                        "${ExampleVocab.baseUri}manufacturer" to mapOf(JsonLdKeywords.id to manufacturerIriValue)
                    )
                )
            )
        ).chain { processedChange ->
            kg.finalize(ChangeFinalizeRequest(podUri, processedChange.id))
        }.await().indefinitely()
    }

    // -----------------------------------------------------------------------
    // @hidden directive tests
    // -----------------------------------------------------------------------

    /**
     * A @hidden field with @filter still restricts the result set — only persons whose
     * email matches the filter are returned, even though so_email is not requested and
     * will not appear in the response.
     */
    @Test
    @TestSecurity(user = "alice")
    fun testHiddenFieldFiltersData() {
        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(query = "{ persons { id so_givenName } }"))
            .post("{podId}/slices/{sliceId}/query", podName, hiddenSliceName)
            .then().statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        val returned = result.getDataField<List<Map<String, Any>>>("persons")!!
        assertEquals(
            personsVisibleInHiddenSlice.map { it[JsonLdKeywords.id] }.toSet(),
            returned.map { it[FIELD_ID_NAME] }.toSet(),
            "Only persons whose hidden email matches the filter should be returned"
        )
        // The hidden field must not leak into the response
        assertTrue(
            returned.none { it.containsKey("so_email") },
            "The @hidden field so_email must not appear in the response"
        )
    }

    /**
     * Attempting to select a @hidden field in a query must be rejected with a
     * validation error — the field should not be selectable by clients.
     */
    @Test
    @TestSecurity(user = "alice")
    fun testHiddenFieldCannotBeQueried() {
        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(query = "{ persons { id so_givenName so_email } }"))
            .post("{podId}/slices/{sliceId}/query", podName, hiddenSliceName)
            .then().statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        assertNotNull(result.errors, "Querying a @hidden field should produce errors")
        assertTrue(
            result.errors!!.isNotEmpty(),
            "There should be at least one validation error when selecting a @hidden field"
        )
        assertTrue(
            result.errors!!.any { error ->
                error.toString().contains("so_email", ignoreCase = true)
            },
            "The error message should mention the offending field name 'so_email'"
        )
    }

    /**
     * Introspection of the Slice type must not expose the @hidden field — it should be
     * absent from the list of fields returned by __type.
     */
    @Test
    @TestSecurity(user = "alice")
    fun testHiddenFieldNotVisibleInIntrospection() {
        val introspectionQuery = """
            {
              __type(name: "ex_Person") {
                fields {
                  name
                }
              }
            }
        """.trimIndent()

        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(query = introspectionQuery))
            .post("{podId}/slices/{sliceId}/query", podName, hiddenSliceName)
            .then().statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        @Suppress("UNCHECKED_CAST")
        val typeInfo = result.getDataField<Map<String, Any>>("__type")
        @Suppress("UNCHECKED_CAST")
        val fieldNames = (typeInfo?.get("fields") as? List<Map<String, Any>>)
            ?.map { it["name"] as String }
            ?: emptyList()

        assertFalse(
            fieldNames.contains("so_email"),
            "The @hidden field so_email must not appear in introspection results"
        )
        assertTrue(
            fieldNames.contains("so_givenName"),
            "Non-hidden field so_givenName should still appear in introspection"
        )
    }

    // -----------------------------------------------------------------------
    // @mustExist tests
    // -----------------------------------------------------------------------

    /**
     * When the client selects the guarded field (so_email), only qualifying persons
     * are returned — this is the baseline filter behaviour.
     */
    @Test
    @TestSecurity(user = "alice")
    fun testMustExistWithFieldSelected() {
        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(query = "{ persons { id so_givenName so_email } }"))
            .post("{podId}/slices/{sliceId}/query", podName, mustExistSliceName)
            .then().statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        val returned = result.getDataField<List<Map<String, Any>>>("persons")!!
        assertEquals(
            personsWithQualifyingEmail.map { it[JsonLdKeywords.id] }.toSet(),
            returned.map { it[FIELD_ID_NAME] }.toSet(),
            "Only persons with a qualifying email should be returned when so_email is selected"
        )
        // All returned persons should have an email ending with the qualifying domain
        assertTrue(
            returned.all { person ->
                @Suppress("UNCHECKED_CAST")
                (person["so_email"] as? List<String>)?.all { it.endsWith(qualifyingDomain) } == true
            },
            "All returned emails should end with the qualifying domain"
        )
    }

    /**
     * When the client does NOT select so_email, @mustExist still enforces the
     * membership boundary — only the qualifying persons must appear.
     *
     * Without @mustExist the filter would be silently dropped when the field is
     * absent from the selection set, allowing non-qualifying persons to leak through.
     */
    @Test
    @TestSecurity(user = "alice")
    fun testMustExistWithFieldOmitted() {
        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(query = "{ persons { id so_givenName } }"))
            .post("{podId}/slices/{sliceId}/query", podName, mustExistSliceName)
            .then().statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        val returned = result.getDataField<List<Map<String, Any>>>("persons")!!
        assertEquals(
            personsWithQualifyingEmail.map { it[JsonLdKeywords.id] }.toSet(),
            returned.map { it[FIELD_ID_NAME] }.toSet(),
            "Even when so_email is omitted from the selection set, @mustExist should restrict results to qualifying persons only"
        )
    }

    // -----------------------------------------------------------------------
    // RDFNode / multi-type field tests
    // -----------------------------------------------------------------------

    /**
     * An RDFNode field can hold either a literal value or a resource IRI.
     * The _rawRDF sub-field exposes the raw RDF representation for each case.
     */
    @Test
    @TestSecurity(user = "alice")
    fun testRdfNodeFieldWithLiteralAndIri() {
        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                QueryInputImpl(
                    query = """
                        {
                          instruments {
                            id
                            ex_manufacturer {
                              _rawRDF
                            }
                          }
                        }
                    """.trimIndent()
                )
            )
            .post("{podId}/slices/{sliceId}/query", podName, rdfNodeSliceName)
            .then().statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        val instruments = result.getDataField<List<Map<String, Any>>>("instruments")!!
        val byId = instruments.associateBy { it[FIELD_ID_NAME] as String }

        assertTrue(byId.containsKey(instrumentLiteralId), "Literal-manufacturer instrument should be present")
        assertTrue(byId.containsKey(instrumentIriId), "IRI-manufacturer instrument should be present")

        // Instrument with a literal manufacturer — _rawRDF should have @value + @type
        val literalRawRDF = asNodeEntries(byId[instrumentLiteralId]!!["ex_manufacturer"])
            .first()[FIELD_RAW_RDF_NAME] as Map<String, Any>
        assertEquals(manufacturerLiteralValue, literalRawRDF[JsonLdKeywords.value],
            "Literal manufacturer should be exposed as @value in _rawRDF")
        assertEquals(XSDVocab.string, literalRawRDF[JsonLdKeywords.type],
            "Literal manufacturer should have XSD string type in _rawRDF")

        // Instrument with an IRI manufacturer — _rawRDF should have @id
        val iriRawRDF = asNodeEntries(byId[instrumentIriId]!!["ex_manufacturer"])
            .first()[FIELD_RAW_RDF_NAME] as Map<String, Any>
        assertEquals(manufacturerIriValue, iriRawRDF[JsonLdKeywords.id],
            "IRI manufacturer should be exposed as @id in _rawRDF")
    }

    /**
     * Both a literal and an IRI value for the same predicate can coexist on the same
     * resource.  When queried via an RDFNode field, both should be present in the result.
     */
    @Test
    @TestSecurity(user = "alice")
    fun testRdfNodeFieldWithMixedValuesOnSameResource() {
        val mixedInstrumentId = "${ExampleVocab.baseUri}instruments/mixed-maker"

        // Insert an instrument that has BOTH a literal and an IRI for ex:manufacturer
        kg.process(
            ChangeRequest(
                id = ChangeRequestId.generate("$podUri/changes").encode(),
                changeId = StateId.generate(),
                context = emptyMap(),
                requestingUser = "alice",
                podId = podUri,
                insert = listOf(
                    mapOf(
                        JsonLdKeywords.id to mixedInstrumentId,
                        JsonLdKeywords.type to "${ExampleVocab.baseUri}Instrument",
                        "${ExampleVocab.baseUri}manufacturer" to listOf(
                            manufacturerLiteralValue,
                            mapOf(JsonLdKeywords.id to manufacturerIriValue)
                        )
                    )
                )
            )
        ).chain { processedChange ->
            kg.finalize(ChangeFinalizeRequest(podUri, processedChange.id))
        }.await().indefinitely()

        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                QueryInputImpl(
                    query = """
                        {
                          instruments {
                            id
                            ex_manufacturer {
                              _rawRDF
                            }
                          }
                        }
                    """.trimIndent()
                )
            )
            .post("{podId}/slices/{sliceId}/query", podName, rdfNodeSliceName)
            .then().statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        val instruments = result.getDataField<List<Map<String, Any>>>("instruments")!!
        val mixed = instruments.find { it[FIELD_ID_NAME] == mixedInstrumentId }!!

        val manufacturerEntries = asNodeEntries(mixed["ex_manufacturer"])
        val rawRDFEntries = manufacturerEntries.map { it[FIELD_RAW_RDF_NAME] as Map<String, Any> }

        val literalValues = rawRDFEntries.mapNotNull { it[JsonLdKeywords.value] }.toSet()
        val iriValues = rawRDFEntries.mapNotNull { it[JsonLdKeywords.id] }.toSet()

        assertEquals(setOf(manufacturerLiteralValue), literalValues,
            "The literal manufacturer value should be present in _rawRDF")
        assertEquals(setOf(manufacturerIriValue), iriValues,
            "The IRI manufacturer value should be present in _rawRDF")
    }

    private fun asNodeEntries(value: Any?): List<Map<String, Any>> {
        @Suppress("UNCHECKED_CAST")
        return when (value) {
            is List<*> -> value.filterIsInstance<Map<String, Any>>()
            is Map<*, *> -> listOf(value as Map<String, Any>)
            else -> emptyList()
        }
    }
}

