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
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.utils.idgen.ChangeRequestId
import kvasir.utils.idgen.StateId
import kvasir.utils.test.commons.AbstractPodTest
import kvasir.utils.test.commons.TestConstants
import kvasir.utils.test.commons.getDataField
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * Tests the multi-type fields functionality for Slices:
 * - RDFNode fields (IRI-or-literal)
 * - BoxedLiteral fields (multi-literal types)
 */
@QuarkusTest
@TestHTTPEndpoint(GraphSlicesApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MultiTypeFieldsTest : AbstractPodTest() {

    @Inject
    lateinit var kg: KnowledgeGraph

    @Inject
    lateinit var repositoryFactory: RepositoryFactory

    val sliceName = "instruments-test"
    lateinit var sliceUri: String

    val instrument1Id = "http://example.org/instruments/1"
    val instrument2Id = "http://example.org/instruments/2"
    val instrument3Id = "http://example.org/instruments/3"
    val instrument4Id = "http://example.org/instruments/4"

    @Test
    @Order(1)
    @TestSecurity(user = "alice")
    fun testSetupSlice() {
        sliceUri = "$podUri/slices/$sliceName"
        val sliceDefinition = """
            type Query {
                instruments: [ex_Instrument]
                instrument(id: ID!): ex_Instrument
            }
            
            type Mutation {
                add(instruments: [InstrumentInput!]): ID!
            }
            
            type ex_Instrument {
                id: ID!
                ex_name: String
                ex_manufacturer: RDFNode
                ex_price: BoxedLiteral
            }
            
            input InstrumentInput @class(iri: "ex:Instrument") {
                id: ID!
                name: String @predicate(iri: "ex:name")
                manufacturer: String @predicate(iri: "ex:manufacturer")
                manufacturerRef: ID @predicate(iri: "ex:manufacturer")
                priceString: String @predicate(iri: "ex:price")
                priceDecimal: Float @predicate(iri: "ex:price")
            }
        """.trimIndent()

        val slice = Slice(
            id = sliceUri,
            name = sliceName,
            description = "Test slice for multi-type fields",
            createdBy = "alice",
            context = TestConstants.CONTEXT,
            schema = EmbeddedSliceSchema(sliceDefinition)
        )
        repositoryFactory.getVersionedRepository(Slice::class, podUri).persist(slice).await().indefinitely()
    }

    @Test
    @Order(2)
    @TestSecurity(user = "alice")
    fun testInsertInstrumentWithLiteralManufacturer() {
        // Insert instrument with manufacturer as a literal String
        val mutation = """
            mutation {
                add(instruments: [
                    {
                        id: "$instrument1Id"
                        name: "Stratocaster"
                        manufacturer: "Fender"
                        priceDecimal: 1299.99
                    }
                ])
            }
        """.trimIndent()

        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(mutation))
            .post("$sliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)

        val changeId = result.getDataField<String>("add")!!
        testHelpers.waitForChangeRequest(changeId, podUri, ChangeStatusCode.COMMITTED).await().indefinitely()
    }

    @Test
    @Order(3)
    @TestSecurity(user = "alice")
    fun testInsertInstrumentWithIRIManufacturer() {
        // Insert instrument with manufacturer as an IRI reference
        val mutation = """
            mutation {
                add(instruments: [
                    {
                        id: "$instrument2Id"
                        name: "ESP Eclipse"
                        manufacturerRef: "https://www.espguitars.com"
                        priceString: "2500 USD"
                    }
                ])
            }
        """.trimIndent()

        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(mutation))
            .post("$sliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)

        val changeId = result.getDataField<String>("add")!!
        testHelpers.waitForChangeRequest(changeId, podUri, ChangeStatusCode.COMMITTED).await().indefinitely()
    }

    @Test
    @Order(4)
    @TestSecurity(user = "alice")
    fun testInsertMultipleInstrumentsWithMixedTypes() {
        // Insert multiple instruments with different type combinations
        val mutation = """
            mutation {
                add(instruments: [
                    {
                        id: "$instrument3Id"
                        name: "Les Paul"
                        manufacturer: "Gibson"
                        priceDecimal: 3999.50
                    }
                    {
                        id: "$instrument4Id"
                        name: "Telecaster"
                        manufacturerRef: "https://www.fender.com"
                        priceString: "Contact for pricing"
                    }
                ])
            }
        """.trimIndent()

        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(mutation))
            .post("$sliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)

        val changeId = result.getDataField<String>("add")!!
        testHelpers.waitForChangeRequest(changeId, podUri, ChangeStatusCode.COMMITTED).await().indefinitely()
    }

    @Test
    @Order(5)
    @TestSecurity(user = "alice")
    fun testQueryRDFNodeWithLiteralValue() {
        // Query instrument with literal manufacturer
        val query = """
            {
                instrument(id: "$instrument1Id") {
                    id
                    ex_name
                    ex_manufacturer {
                        _rawRDF
                    }
                    ex_price {
                        _rawRDF
                    }
                }
            }
        """.trimIndent()

        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(query))
            .post("$sliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)

        val instrument = result.getDataField<Map<String, Any>>("instrument")!!
        assertEquals(instrument1Id, instrument["id"])
        assertEquals("Stratocaster", instrument["ex_name"])

        // Check RDFNode (manufacturer) - should be a literal value
        val manufacturer = instrument["ex_manufacturer"] as Map<*, *>
        val manufacturerRaw = manufacturer["_rawRDF"] as Map<*, *>
        assertEquals("Fender", manufacturerRaw["@value"])
        assertEquals("http://www.w3.org/2001/XMLSchema#string", manufacturerRaw["@type"])

        // Check BoxedLiteral (price) - should be a decimal literal
        val price = instrument["ex_price"] as Map<*, *>
        val priceRaw = price["_rawRDF"] as Map<*, *>
        assertTrue(priceRaw.containsKey("@value"))
        assertEquals("http://www.w3.org/2001/XMLSchema#double", priceRaw["@type"])
    }

    @Test
    @Order(6)
    @TestSecurity(user = "alice")
    fun testQueryRDFNodeWithIRIValue() {
        // Query instrument with IRI manufacturer
        val query = """
            {
                instrument(id: "$instrument2Id") {
                    id
                    ex_name
                    ex_manufacturer {
                        _rawRDF
                    }
                    ex_price {
                        _rawRDF
                    }
                }
            }
        """.trimIndent()

        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(query))
            .post("$sliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)

        val instrument = result.getDataField<Map<String, Any>>("instrument")!!
        assertEquals(instrument2Id, instrument["id"])
        assertEquals("ESP Eclipse", instrument["ex_name"])

        // Check RDFNode (manufacturer) - should be an IRI reference
        val manufacturer = instrument["ex_manufacturer"] as Map<*, *>
        val manufacturerRaw = manufacturer["_rawRDF"] as Map<*, *>
        assertEquals("https://www.espguitars.com", manufacturerRaw["@id"])
        assertFalse(manufacturerRaw.containsKey("@value"))

        // Check BoxedLiteral (price) - should be a string literal
        val price = instrument["ex_price"] as Map<*, *>
        val priceRaw = price["_rawRDF"] as Map<*, *>
        assertEquals("2500 USD", priceRaw["@value"])
        assertEquals("http://www.w3.org/2001/XMLSchema#string", priceRaw["@type"])
    }

    @Test
    @Order(7)
    @TestSecurity(user = "alice")
    fun testQueryAllInstruments() {
        // Query all instruments to verify mixed types
        val query = """
            {
                instruments {
                    id
                    ex_name
                    ex_manufacturer {
                        _rawRDF
                    }
                    ex_price {
                        _rawRDF
                    }
                }
            }
        """.trimIndent()

        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(query))
            .post("$sliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)

        val instruments = result.getDataField<List<Map<String, Any>>>("instruments")!!
        assertEquals(4, instruments.size)

        // Verify we have both literal and IRI manufacturers
        val manufacturerTypes = instruments.map { instrument ->
            val manufacturer = instrument["ex_manufacturer"] as Map<*, *>
            val manufacturerRaw = manufacturer["_rawRDF"] as Map<*, *>
            when {
                manufacturerRaw.containsKey("@id") -> "IRI"
                manufacturerRaw.containsKey("@value") -> "Literal"
                else -> "Unknown"
            }
        }
        assertTrue(manufacturerTypes.contains("IRI"))
        assertTrue(manufacturerTypes.contains("Literal"))

        // Verify we have both string and decimal prices
        val priceTypes = instruments.mapNotNull { instrument ->
            val price = instrument["ex_price"] as? Map<*, *>
            val priceRaw = price?.get("_rawRDF") as? Map<*, *>
            priceRaw?.get("@type") as? String
        }
        assertTrue(priceTypes.any { it.contains("string") })
        assertTrue(priceTypes.any { it.contains("double") || it.contains("decimal") })
    }

    @Test
    @Order(8)
    @TestSecurity(user = "alice")
    fun testInsertViaChangesAPI() {
        // Test that the same multi-type fields work via the Changes API
        val instrument5Id = "http://example.org/instruments/5"

        val insertData = mapOf(
            JsonLdKeywords.id to instrument5Id,
            JsonLdKeywords.context to TestConstants.CONTEXT,
            JsonLdKeywords.type to "ex:Instrument",
            "ex:name" to "Jazzmaster",
            "ex:manufacturer" to mapOf(
                JsonLdKeywords.id to "https://www.fender.com"
            ),
            "ex:price" to mapOf(
                JsonLdKeywords.value to 1899.00,
                JsonLdKeywords.type to "http://www.w3.org/2001/XMLSchema#double"
            )
        )

        val changeRequest = ChangeRequest(
            id = ChangeRequestId.generate("$sliceUri/changes").encode(),
            changeId = StateId.generate(),
            context = TestConstants.CONTEXT,
            requestingUser = "alice",
            podId = podUri,
            sliceId = sliceUri,
            insert = listOf(JsonLdHelper.toCompactFQForm(insertData))
        )

        kg.process(changeRequest).chain { processedChange ->
            kg.finalize(ChangeFinalizeRequest(podUri, processedChange.id))
        }.await().indefinitely()

        // Verify the instrument was inserted correctly
        val query = """
            {
                instrument(id: "$instrument5Id") {
                    id
                    ex_name
                    ex_manufacturer {
                        _rawRDF
                    }
                    ex_price {
                        _rawRDF
                    }
                }
            }
        """.trimIndent()

        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(query))
            .post("$sliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)

        val instrument = result.getDataField<Map<String, Any>>("instrument")!!
        assertEquals(instrument5Id, instrument["id"])
        assertEquals("Jazzmaster", instrument["ex_name"])

        // Verify manufacturer is an IRI
        val manufacturer = instrument["ex_manufacturer"] as Map<*, *>
        val manufacturerRaw = manufacturer["_rawRDF"] as Map<*, *>
        assertEquals("https://www.fender.com", manufacturerRaw["@id"])

        // Verify price is a decimal
        val price = instrument["ex_price"] as Map<*, *>
        val priceRaw = price["_rawRDF"] as Map<*, *>
        assertTrue(priceRaw.containsKey("@value"))
    }

    @Test
    @Order(9)
    @TestSecurity(user = "alice")
    fun testQueryWithoutRawRDF() {
        // Test that querying without _rawRDF still works (though less useful for multi-type fields)
        val query = """
            {
                instruments {
                    id
                    ex_name
                }
            }
        """.trimIndent()

        val result = given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(QueryInputImpl(query))
            .post("$sliceUri/query")
            .then().statusCode(200).extract().body().`as`(QueryResult::class.java)

        val instruments = result.getDataField<List<Map<String, Any>>>("instruments")!!
        assertTrue(instruments.size >= 4, "Should have at least 4 instruments")

        // Verify the basic fields are present
        instruments.forEach { instrument ->
            assertTrue(instrument.containsKey("id"))
            assertTrue(instrument.containsKey("ex_name"))
        }
    }
}


