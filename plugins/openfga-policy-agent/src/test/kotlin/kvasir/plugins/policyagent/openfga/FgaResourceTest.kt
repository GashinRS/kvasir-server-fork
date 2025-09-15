package kvasir.plugins.policyagent.openfga

import com.github.jsonldjava.utils.JsonUtils
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import kvasir.definitions.auth.AuthInitializer
import kvasir.definitions.config.KvasirConfig
import kvasir.definitions.kg.Pod
import kvasir.definitions.kg.PodStore
import kvasir.definitions.kg.PodStoreFactory
import kvasir.definitions.rdf.*
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class FgaResourceTest {


    @Inject
    lateinit var authInitializer: AuthInitializer

    @Inject
    lateinit var podStoreFactory: PodStoreFactory

    @ConfigProperty(name = KvasirConfig.BASE_URI_PROPERTY)
    lateinit var baseUri: String

    private lateinit var podId: String

    // Grant alice access to perform queries and mutations on a Slice with ID "test123"
    private lateinit var aliceAccessGrant: String

    // Grant public access to a specific S3 location
    private lateinit var publicAccessGrant: String

    @BeforeAll
    fun setup() {
        podId = "${baseUri}bob"
        // Create a pod store for the test
        val pod = Pod(podId, mapOf())
        podStoreFactory.createPodStore().persist(pod).await().indefinitely()
        // Init openfga-policy-agent
        authInitializer.initializeForPod(podId, "bob", "bob", pod).await().indefinitely()

        aliceAccessGrant = """
            {
             "@context": {
                "kss": "${KvasirVocab.baseUri}",
                "kss-fga": "${FgaVocab.baseUri}"
             },
             "kss:insert": [
                {
                    "@id": "urn:kvasir-user:alice",
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
    @TestSecurity(user = "bob")
    @Order(1)
    fun testCreateRelationships() {
        given()
            .body(aliceAccessGrant)
            .contentType(RDFMediaTypes.JSON_LD)
            .post("/bob/rebac/relationships")
            .then()
            .statusCode(204)

        given()
            .body(publicAccessGrant)
            .contentType(RDFMediaTypes.JSON_LD)
            .post("/bob/rebac/relationships")
            .then()
            .statusCode(204)
    }

    @Test
    @TestSecurity(user = "bob")
    @Order(2)
    fun testReadRelationships() {
        // Read relationships for the user "bob"
        val jsonLdResponse = given()
            .get("/bob/rebac/relationships")
            .then()
            .statusCode(200)
            .extract().body().asString().let { body -> JsonUtils.fromString(body) as JSONObject }

        assertTrue(jsonLdResponse.relationExists("urn:kvasir-user:bob", "kss-fga:User", "kss-fga:owner", podId))
        assertTrue(
            jsonLdResponse.relationExists(
                "urn:kvasir-user:alice",
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
    @TestSecurity(user = "bob")
    @Order(3)
    fun testCheckRelationships() {
        // Check alice can perform a mutation query on the Slice with ID "test123"
        val aliceValidCheck = """
            {
                 "@context": {
                    "kss": "${KvasirVocab.baseUri}",
                    "kss-fga": "${FgaVocab.baseUri}"
                 },
                "@id": "urn:kvasir-user:alice",
                "@type": "kss-fga:User",
                "kss-fga:can_write": {
                    "@id": "$podId/slices/test123/query",
                    "@type": "kss-fga:Resource"
                }
            }
        """.trimIndent()

        val check1 = given()
            .body(aliceValidCheck)
            .contentType(RDFMediaTypes.JSON_LD)
            .post("/bob/rebac/check")
            .then()
            .extract().body().asString().let { body -> JsonUtils.fromString(body) as JSONObject }

        assertTrue(check1["kss-fga:allowed"] as Boolean)

        // Check alice cannot access global KG
        val aliceInvalidCheck = """
            {
                 "@context": {
                    "kss": "${KvasirVocab.baseUri}",
                    "kss-fga": "${FgaVocab.baseUri}"
                 },
                "@id": "urn:kvasir-user:alice",
                "@type": "kss-fga:User",
                "kss-fga:can_read": {
                    "@id": "$podId/kg/query",
                    "@type": "kss-fga:Resource"
                }
            }
        """.trimIndent()

        val check2 = given()
            .body(aliceInvalidCheck)
            .contentType(RDFMediaTypes.JSON_LD)
            .post("/bob/rebac/check")
            .then()
            .extract().body().asString().let { body -> JsonUtils.fromString(body) as JSONObject }

        assertFalse(check2["kss-fga:allowed"] as Boolean)
    }

    @Test
    @TestSecurity(user = "bob")
    @Order(4)
    fun testDeleteRelationships() {
        given()
            .body(aliceAccessGrant.replace("kss:insert", "kss:delete"))
            .contentType(RDFMediaTypes.JSON_LD)
            .post("/bob/rebac/relationships")
            .then()
            .statusCode(204)

        given()
            .body(publicAccessGrant.replace("kss:insert", "kss:delete"))
            .contentType(RDFMediaTypes.JSON_LD)
            .post("/bob/rebac/relationships")
            .then()
            .statusCode(204)

        // Verify deletion
        val jsonLdResponse = given()
            .get("/bob/rebac/relationships")
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

    private fun JSONObject.relationExists(
        subject: String,
        subjectType: String,
        relation: String,
        `object`: String
    ): Boolean {
        val resources = this.getJsonArray<JSONObject>(JsonLdKeywords.graph)?.takeIf { it.isNotEmpty() } ?: listOf(this)
        return resources.any {
            it[JsonLdKeywords.id] == subject && it[JsonLdKeywords.type] == subjectType && it.getJsonObject(
                relation
            )?.get(JsonLdKeywords.id) == `object`
        }
    }

}