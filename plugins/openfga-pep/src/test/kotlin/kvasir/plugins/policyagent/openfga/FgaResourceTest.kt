package kvasir.plugins.policyagent.openfga

import com.github.jsonldjava.utils.JsonUtils
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import kvasir.definitions.auth.AuthLifecycleManager
import kvasir.definitions.config.HttpConfig
import kvasir.definitions.kg.Pod
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.rdf.*
import kvasir.utils.test.commons.TestGenerateClientConfig
import kvasir.utils.test.commons.TestPodConfig
import kvasir.utils.test.commons.getTokenForClient
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.util.*

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class FgaResourceTest {


    @Inject
    lateinit var authLifecycleManager: AuthLifecycleManager

    @Inject
    lateinit var repositoryFactory: RepositoryFactory

    @Inject
    lateinit var config: HttpConfig

    // Grant alice access to perform queries and mutations on a Slice with ID "test123"
    private lateinit var aliceAccessGrant: String

    // Grant public access to a specific S3 location
    private lateinit var publicAccessGrant: String

    private val testRunId = UUID.randomUUID().toString()

    private lateinit var podId: String

    @BeforeAll
    fun setup() {
        podId = "${config.baseUri()}${testRunId}"
        // Create a pod store for the test
        val pod = Pod(podId, "{}")
        repositoryFactory.getRepository(Pod::class).persist(pod).await().indefinitely()
        // Init openfga-policy-agent
        authLifecycleManager.initializeForPod(
            pod, TestPodConfig(
                testRunId, "bob", listOf(
                    TestGenerateClientConfig(BOB_CLIENT_NAME, BOB_CLIENT_SECRET, true)
                )
            )
        ).await().indefinitely()

        aliceAccessGrant = """
            {
             "@context": {
                "kss": "${KvasirVocab.baseUri}",
                "kss-fga": "${FgaVocab.baseUri}"
             },
             "kss:insert": [
                {
                    "@id": "urn:kvasir-user:service-account-alice-client",
                    "@type": "kss-fga:User",
                    "kss-fga:reader": {
                        "@id": "$podId/slices/test123",
                        "@type": "kss-fga:Resource"
                    },
                    "kss-fga:writer": {
                        "@id": "$podId/slices/test123",
                        "@type": "kss-fga:Resource"
                    }
                }
             ]             
            }
        """.trimIndent()

        publicAccessGrant = """
            {
             "@context": {
                "kss": "${KvasirVocab.baseUri}",
                "kss-fga": "${FgaVocab.baseUri}"
             },
             "kss:insert": [
                {
                    "@id": "urn:kvasir-user:anonymous",
                    "@type": "kss-fga:User",
                    "kss-fga:reader": {
                        "@id": "$podId/s3/subfolder/someFile.txt",
                        "@type": "kss-fga:Resource"
                    }
                }
             ]             
            }
        """.trimIndent()
    }

    @Test
    @Order(1)
    fun testCreateRelationships() {
        given()
            .auth().oauth2(getTokenForClient(BOB_CLIENT_NAME, BOB_CLIENT_SECRET))
            .body(aliceAccessGrant)
            .contentType(RDFMediaTypes.JSON_LD)
            .post("/$testRunId/rebac/relationships")
            .then()
            .statusCode(204)

        given()
            .auth().oauth2(getTokenForClient(BOB_CLIENT_NAME, BOB_CLIENT_SECRET))
            .body(publicAccessGrant)
            .contentType(RDFMediaTypes.JSON_LD)
            .post("/$testRunId/rebac/relationships")
            .then()
            .statusCode(204)
    }

    @Test
    @Order(2)
    fun testReadRelationships() {
        // Read relationships for the user "bob"
        val jsonLdResponse = given()
            .auth().oauth2(getTokenForClient(BOB_CLIENT_NAME, BOB_CLIENT_SECRET))
            .get("/$testRunId/rebac/relationships")
            .then()
            .statusCode(200)
            .extract().body().asString().let { body -> JsonUtils.fromString(body) as JSONObject }

        assertTrue(jsonLdResponse.relationExists("urn:kvasir-user:bob", "kss-fga:User", "kss-fga:owner", podId))
        assertTrue(
            jsonLdResponse.relationExists(
                "urn:kvasir-user:service-account-alice-client",
                "kss-fga:User",
                "kss-fga:reader",
                "$podId/slices/test123"
            )
        )
        assertTrue(
            jsonLdResponse.relationExists(
                "urn:kvasir-user:anonymous",
                "kss-fga:User",
                "kss-fga:reader",
                "$podId/s3/subfolder/someFile.txt"
            )
        )
    }

    @Test
    @Order(3)
    fun testCheckRelationships() {
        // Check alice can perform a mutation query on the Slice with ID "test123"
        val aliceValidCheck = """
            {
                 "@context": {
                    "kss": "${KvasirVocab.baseUri}",
                    "kss-fga": "${FgaVocab.baseUri}"
                 },
                "@id": "urn:kvasir-user:service-account-alice-client",
                "@type": "kss-fga:User",
                "kss-fga:can_write": {
                    "@id": "$podId/slices/test123/query",
                    "@type": "kss-fga:Resource"
                }
            }
        """.trimIndent()

        val check1 = given()
            .auth().oauth2(getTokenForClient(BOB_CLIENT_NAME, BOB_CLIENT_SECRET))
            .body(aliceValidCheck)
            .contentType(RDFMediaTypes.JSON_LD)
            .post("/$testRunId/rebac/check")
            .then()
            .extract().body().asString().let { body -> JsonLdHelper.decode(body, CheckResult::class.java) }

        assertTrue(check1.allowed)

        // Check alice cannot access global KG
        val aliceInvalidCheck = """
            {
                 "@context": {
                    "kss": "${KvasirVocab.baseUri}",
                    "kss-fga": "${FgaVocab.baseUri}"
                 },
                "@id": "urn:kvasir-user:service-account-alice-client",
                "@type": "kss-fga:User",
                "kss-fga:can_read": {
                    "@id": "$podId/kg/query",
                    "@type": "kss-fga:Resource"
                }
            }
        """.trimIndent()

        val check2 = given()
            .auth().oauth2(getTokenForClient(BOB_CLIENT_NAME, BOB_CLIENT_SECRET))
            .body(aliceInvalidCheck)
            .contentType(RDFMediaTypes.JSON_LD)
            .post("/$testRunId/rebac/check")
            .then()
            .extract().body().asString().let { body -> JsonLdHelper.decode(body, CheckResult::class.java) }

        assertFalse(check2.allowed)
    }

    @Test
    @Order(4)
    fun testDeleteRelationships() {
        given()
            .auth().oauth2(getTokenForClient(BOB_CLIENT_NAME, BOB_CLIENT_SECRET))
            .body(aliceAccessGrant.replace("kss:insert", "kss:delete"))
            .contentType(RDFMediaTypes.JSON_LD)
            .post("/$testRunId/rebac/relationships")
            .then()
            .statusCode(204)

        given()
            .auth().oauth2(getTokenForClient(BOB_CLIENT_NAME, BOB_CLIENT_SECRET))
            .body(publicAccessGrant.replace("kss:insert", "kss:delete"))
            .contentType(RDFMediaTypes.JSON_LD)
            .post("/$testRunId/rebac/relationships")
            .then()
            .statusCode(204)

        // Verify deletion
        val jsonLdResponse = given()
            .auth().oauth2(getTokenForClient(BOB_CLIENT_NAME, BOB_CLIENT_SECRET))
            .get("/$testRunId/rebac/relationships")
            .then()
            .statusCode(200)
            .extract().body().asString().let { body -> JsonUtils.fromString(body) as JSONObject }

        assertTrue(jsonLdResponse.relationExists("urn:kvasir-user:bob", "kss-fga:User", "kss-fga:owner", podId))
        assertFalse(
            jsonLdResponse.relationExists(
                "urn:kvasir-user:alice",
                "kss-fga:User",
                "kss-fga:reader",
                "$podId/slices/test123"
            )
        )
        assertFalse(
            jsonLdResponse.relationExists(
                "urn:kvasir-user:anonymous",
                "kss-fga:User",
                "kss-fga:reader",
                "$podId/s3/subfolder/someFile.txt"
            )
        )
    }

    @Test
    fun testWriteAndReadRelationshipWithCondition() {
        val delegateToUma = """
            {
             "@context": {
                "kss": "${KvasirVocab.baseUri}",
                "kss-fga": "${FgaVocab.baseUri}"
             },
             "kss:insert": [
                {
                    "@id": "${OpenFgaConstants.WILDCARD_ID}",
                    "@type": "kss-fga:User",
                    "kss-fga:owner": {
                        "@id": "$podId",
                        "@type": "kss-fga:Resource",
                        "kss-fga:external_access": [
                            {
                                "@id": "kss-fga:uma"
                            }
                        ]
                    }
                }
             ]             
            }
        """.trimIndent()

        given()
            .auth().oauth2(getTokenForClient(BOB_CLIENT_NAME, BOB_CLIENT_SECRET))
            .body(delegateToUma)
            .contentType(RDFMediaTypes.JSON_LD)
            .post("/$testRunId/rebac/relationships")
            .then()
            .statusCode(204)

        // Now try to read back the relationship
        // Read relationships for the user "bob"
        val jsonLdResponse = given()
            .auth().oauth2(getTokenForClient(BOB_CLIENT_NAME, BOB_CLIENT_SECRET))
            .get("/$testRunId/rebac/relationships")
            .then()
            .statusCode(200)
            .extract().body().asString().let { body -> JsonUtils.fromString(body) as JSONObject }

        assertTrue(
            jsonLdResponse.relationExists(
                OpenFgaConstants.WILDCARD_ID,
                "kss-fga:User",
                "kss-fga:owner",
                podId
            )
        )

        assertTrue(jsonLdResponse.relationExists(podId, "kss-fga:Resource", "kss-fga:external_access", "kss-fga:Uma"))
    }

    private fun JSONObject.relationExists(
        subject: String,
        subjectType: String,
        relation: String,
        `object`: String
    ): Boolean {
        val resources = this.getJsonArray<JSONObject>(JsonLdKeywords.graph)?.takeIf { it.isNotEmpty() } ?: listOf(this)
        val allResources = resources.flatMap { topLevelResource ->
            listOf(topLevelResource) + topLevelResource.filter {
                it.key !in setOf(
                    JsonLdKeywords.id,
                    JsonLdKeywords.type
                )
            }.map { it.value as JSONObject }
        }
        return allResources.any {
            it[JsonLdKeywords.id] == subject && it[JsonLdKeywords.type] == subjectType && it.getJsonObject(
                relation
            )?.get(JsonLdKeywords.id) == `object`
        }
    }

}