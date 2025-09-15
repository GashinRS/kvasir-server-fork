package kvasir.services.api.kg.streams

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.get
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.ws.rs.core.HttpHeaders
import kvasir.definitions.kg.LifeCycleEvent
import kvasir.definitions.kg.LifeCycleEventType
import kvasir.definitions.kg.QueryRequestEvent
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.definitions.storage.StorageEvent
import kvasir.definitions.storage.StorageEventType
import kvasir.services.api.kg.query.QueryInputImpl
import kvasir.services.api.kg.query.SliceInput
import kvasir.utils.test.clickhouse.ClickhouseTestResource
import kvasir.utils.test.commons.TestConstants
import kvasir.utils.test.commons.TestHelpers
import kvasir.utils.test.http.ParseEventsFromJsonLD
import kvasir.utils.test.http.SSEClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

@QuarkusTest
@QuarkusTestResource(ClickhouseTestResource::class)
class StreamApiTest {

    @Inject
    lateinit var testHelpers: TestHelpers

    @Test
    @TestSecurity(user = "alice")
    fun testQueryRequestEvents() {
        val podUri = testHelpers.getPodUri(TestConstants.TEST_POD_1_ID)
        SSEClient(
            "${podUri}/events/query?receiveBacklog=true",
            ParseEventsFromJsonLD(QueryRequestEvent::class.java)
        ).use { sseClient ->
            val query = """
                {
                  Resource {
                    id
                    _types
                  }
                }
            """.trimIndent()
            // Perform query
            testHelpers.queryKGViaHTTP(QueryInputImpl(query), podUri)

            val receivedEvent = sseClient.openStream().toUni().await().atMost(Duration.ofSeconds(5))
            assertEquals(query, receivedEvent.query)
        }
    }

    @Test
    @TestSecurity(user = "alice")
    fun testLifeCycleEvents() {
        val podUri = testHelpers.getPodUri(TestConstants.TEST_POD_1_ID)
        SSEClient(
            "${podUri}/events/life-cycle?receiveBacklog=true",
            ParseEventsFromJsonLD(LifeCycleEvent::class.java)
        ).use { client ->
            // Create a slice
            val sliceDefinition = """
            type Query {
                persons: [ex_Person!]!
                person(id: ID!): ex_Person
            }
            
            type ex_Person {
                id: ID!
                so_givenName: String!
                so_familyName: String!
            }
        """.trimIndent()
            val input = SliceInput(
                name = "test-slice",
                context = TestConstants.CONTEXT,
                schema = sliceDefinition
            )

            val sliceUri = given()
                .contentType(RDFMediaTypes.JSON_LD)
                .body(input)
                .post("$podUri/slices")
                .then()
                .statusCode(201)
                .extract().header(HttpHeaders.LOCATION)


            val receivedEvent = client.openStream().toUni().await().atMost(Duration.ofSeconds(5))
            assertEquals(sliceUri, receivedEvent.sliceId)
            assertEquals(LifeCycleEventType.SLICE_CREATED, receivedEvent.type)
        }
    }

    @Test
    @TestSecurity(user = "alice")
    fun testStorageMutationEvents() {
        val podUri = testHelpers.getPodUri(TestConstants.TEST_POD_1_ID)
        SSEClient(
            "${podUri}/events/s3?receiveBacklog=true",
            ParseEventsFromJsonLD(StorageEvent::class.java)
        ).use { client ->
            // Upload a file
            val content = "Hello, World!"
            given()
                .body(content)
                .contentType(ContentType.TEXT)
                .`when`().put("$podUri/s3/test.txt")
                .then().statusCode(200)
            // Read the previously uploaded file
            get("$podUri/s3/test.txt")
                .then().statusCode(200)

            val (firstEvent, secondEvent) =
                client.openStream().select().first(2).collect().asList().await().atMost(Duration.ofSeconds(5))
            assertEquals("$podUri/s3/test.txt", firstEvent.externalObjectUri)
            assertEquals(StorageEventType.PUT_OBJECT, firstEvent.type)
            assertEquals("$podUri/s3/test.txt", secondEvent.externalObjectUri)
            assertEquals(StorageEventType.GET_OBJECT, secondEvent.type)
        }
    }

}