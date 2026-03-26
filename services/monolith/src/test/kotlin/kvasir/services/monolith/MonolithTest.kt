package kvasir.services.monolith

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.config.HttpConfig
import kvasir.definitions.kg.QueryResult
import kvasir.definitions.rdf.*
import kvasir.services.api.kg.changes.ChangeRequestInput
import kvasir.services.api.kg.query.QueryInputWithContext
import kvasir.services.api.pods.RegisterPodInput
import kvasir.utils.test.commons.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.*

const val TEST_USER_ID = "test-user"
const val TEST_CLIENT_ID = "test-client"
const val TEST_CLIENT_SECRET = "test-client-secret"

// Run some basic tests on the monolith
@QuarkusTest
class MonolithTest {

    @Inject
    private lateinit var kvasirHttpConfig: HttpConfig

    @Inject
    private lateinit var testHelpers: TestHelpers

    @Test
    fun testPodCreationAndBasicUse() {
        val podName = UUID.randomUUID().toString()
        val registrationInput = RegisterPodInput(
            name = podName,
            ownerUserId = TEST_USER_ID,
            adminClientId = TEST_CLIENT_ID,
            adminClientSecret = TEST_CLIENT_SECRET
        )
        val podUri = given()
            .body(JsonLdHelper.encode(registrationInput))
            .contentType(RDFMediaTypes.JSON_LD)
            .post(kvasirHttpConfig.baseUri())
            .then()
            .statusCode(201)
            .extract().header(HttpHeaders.LOCATION)

        // Ingest some data
        val testData = TestDataGenerator.generatePersonData(5)

        // Wait the data is processed
        testHelpers.requestChangeViaHTTPSync(
            JsonLdHelper.encode(ChangeRequestInput(insert = testData)),
            podUri,
            optionalAuthToken = getTokenForClient(TEST_CLIENT_ID, TEST_CLIENT_SECRET)
        )

        // Query the data
        val (selectedPersonID, selectedPersonGivenName) = testData.random()
            .let { (it[JsonLdKeywords.id]!! as String) to (it[SchemaVocab.givenName]!! as String) }
        val graphql = """
            {
                ex_Person(id: "$selectedPersonID") {
                    id
                    so_givenName
                }
            }
        """.trimIndent()

        val queryResult = given()
            .auth().oauth2(getTokenForClient(TEST_CLIENT_ID, TEST_CLIENT_SECRET))
            .body(QueryInputWithContext(query = graphql, providedContext = TestConstants.CONTEXT))
            .contentType(MediaType.APPLICATION_JSON)
            .post("$podUri/query")
            .then()
            .statusCode(200)
            .extract().body().`as`(QueryResult::class.java)

        val resultPersonGivenName =
            queryResult.data?.getJsonArray<JSONObject>("ex_Person")?.get(0)?.getJsonArray<String>("so_givenName")
                ?.firstOrNull()
        assertEquals(selectedPersonGivenName, resultPersonGivenName)
    }

}