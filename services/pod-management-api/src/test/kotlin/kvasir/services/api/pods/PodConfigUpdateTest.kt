package kvasir.services.api.pods

import io.quarkus.test.common.http.TestHTTPEndpoint
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.vertx.core.json.JsonObject
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.kg.Pod
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.utils.test.commons.AbstractPodTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

@QuarkusTest
@TestHTTPEndpoint(PodManagementApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PodConfigUpdateTest : AbstractPodTest() {

    @Test
    @Order(1)
    @TestSecurity(user = "alice")
    fun testGetInitialRuntimeConfig() {
        val runtimeConfig = given()
            .accept(MediaType.APPLICATION_JSON)
            .get("{podId}/runtime-config", podName)
            .then()
            .statusCode(200)
            .extract().body().asString().let { JsonObject(it) }

        val defaultContext = runtimeConfig.getJsonObject("default-context")
        assertNotNull(defaultContext)
        // Initial config from TestPodConfig includes "ex" prefix
        assertTrue(defaultContext.containsKey("ex"))
        assertEquals("http://example.org/", defaultContext.getString("ex"))
    }

    @Test
    @Order(2)
    @TestSecurity(user = "alice")
    fun testUpdateDefaultContext() {
        val newConfig = JsonObject()
            .put(
                "default-context", JsonObject()
                    .put("custom", "http://custom.example.org/")
                    .put("foaf", "http://xmlns.com/foaf/0.1/")
            )
            .encode()

        given()
            .contentType(RDFMediaTypes.JSON_LD)
            .body(JsonLdHelper.encode(UpdatePodInput(configuration = newConfig)))
            .put("{podId}", podName)
            .then()
            .statusCode(204)

        // Verify runtime config reflects the updated context
        val runtimeConfig = given()
            .accept(MediaType.APPLICATION_JSON)
            .get("{podId}/runtime-config", podName)
            .then()
            .statusCode(200)
            .extract().body().asString().let { JsonObject(it) }

        val defaultContext = runtimeConfig.getJsonObject("default-context")
        assertNotNull(defaultContext)
        assertEquals("http://custom.example.org/", defaultContext.getString("custom"))
        assertEquals("http://xmlns.com/foaf/0.1/", defaultContext.getString("foaf"))
        // Original keys should no longer be present since the full context was replaced
        assertFalse(defaultContext.containsKey("ex"))
    }

    @Test
    @Order(3)
    @TestSecurity(user = "alice")
    fun testUpdateContextAndAutoIngest() {
        val newConfig = JsonObject()
            .put("auto-ingest-rdf", true)
            .put(
                "default-context", JsonObject()
                    .put("myns", "http://my-namespace.example.org/")
            )
            .encode()

        given()
            .contentType(RDFMediaTypes.JSON_LD)
            .body(JsonLdHelper.encode(UpdatePodInput(configuration = newConfig)))
            .put("{podId}", podName)
            .then()
            .statusCode(204)

        val runtimeConfig = given()
            .accept(MediaType.APPLICATION_JSON)
            .get("{podId}/runtime-config", podName)
            .then()
            .statusCode(200)
            .extract().body().asString().let { JsonObject(it) }

        assertTrue(runtimeConfig.getBoolean("auto-ingest-rdf"))
        val ctx = runtimeConfig.getJsonObject("default-context")
        assertEquals("http://my-namespace.example.org/", ctx.getString("myns"))
    }

    @Test
    @Order(4)
    @TestSecurity(user = "alice")
    fun testGetPodReflectsUpdatedConfig() {
        val body = given()
            .accept(RDFMediaTypes.JSON_LD)
            .get("{podId}", podName)
            .then()
            .statusCode(200)
            .extract().body().asString()

        val pod = JsonLdHelper.decode(body, Pod::class.java)
        val storedConfig = JsonObject(pod.configuration)
        val storedContext = storedConfig.getJsonObject("default-context")
        assertNotNull(storedContext)
        assertEquals("http://my-namespace.example.org/", storedContext.getString("myns"))
    }

    @Test
    @Order(5)
    @TestSecurity(user = "alice")
    fun testUpdateNonExistentPodReturns404() {
        val newConfig = JsonObject()
            .put("default-context", JsonObject())
            .encode()

        given()
            .contentType(RDFMediaTypes.JSON_LD)
            .body(JsonLdHelper.encode(UpdatePodInput(configuration = newConfig)))
            .put("{podId}", "non-existent-pod-id")
            .then()
            .statusCode(404)
    }

    @Test
    @Order(6)
    @TestSecurity(user = "alice")
    fun testUpdatePodUnlockOidc() {
        // Get default oidc value
        val runtimeConfig = given()
            .accept(MediaType.APPLICATION_JSON)
            .get("{podId}/runtime-config", podName)
            .then()
            .statusCode(200)
            .extract().body().asString().let { JsonObject(it) }
        val oidcConfig = runtimeConfig.getJsonObject("auth").getJsonObject("oidc")

        // Get current pod config
        val body = given()
            .accept(RDFMediaTypes.JSON_LD)
            .get("{podId}", podName)
            .then()
            .statusCode(200)
            .extract().body().asString()
        val pod = JsonLdHelper.decode(body, Pod::class.java)

        // Update oidc config
        val newConfig = JsonObject(pod.configuration)
        val updatedOidcConfig = oidcConfig.copy().put("jwt-allowed-clock-skew-seconds", 666);
        newConfig.put("auth", JsonObject().put("oidc", updatedOidcConfig))

        // PUT new config to pod
        given()
            .contentType(RDFMediaTypes.JSON_LD)
            .body(JsonLdHelper.encode(UpdatePodInput(configuration = newConfig.encode())))
            .put("{podId}", podName)
            .then()
            .statusCode(204)

        // Get current pod config and check change
        val body2 = given()
            .accept(RDFMediaTypes.JSON_LD)
            .get("{podId}", podName)
            .then()
            .statusCode(200)
            .extract().body().asString()
        val pod2 = JsonLdHelper.decode(body2, Pod::class.java)
        assertEquals(
            666,
            JsonObject(pod2.configuration).getJsonObject("auth").getJsonObject("oidc")
                .getInteger("jwt-allowed-clock-skew-seconds")
        )

        // Runtime should also reflect the change
        given()
            .accept(MediaType.APPLICATION_JSON)
            .get("{podId}/runtime-config", podName)
            .then()
            .statusCode(200)
            .extract().body().asString().let {
                assertEquals(
                    666,
                    JsonObject(it).getJsonObject("auth").getJsonObject("oidc")
                        .getInteger("jwt-allowed-clock-skew-seconds")
                )
            }

    }

    @Test
    @Order(7)
    @TestSecurity(user = "alice")
    fun testUpdatePodLockOidc() {
        // 1. Setting key to null explicitely
        // Get current pod config
        val body = given()
            .accept(RDFMediaTypes.JSON_LD)
            .get("{podId}", podName)
            .then()
            .statusCode(200)
            .extract().body().asString()
        val pod = JsonLdHelper.decode(body, Pod::class.java)

        // Update oidc config
        val newConfig = JsonObject(pod.configuration)
        newConfig.put("auth", JsonObject().put("oidc", null))

        // PUT new config to pod
        given()
            .contentType(RDFMediaTypes.JSON_LD)
            .body(JsonLdHelper.encode(UpdatePodInput(configuration = newConfig.encode())))
            .put("{podId}", podName)
            .then()
            .statusCode(204)

        // Get current pod config and check change
        val body2 = given()
            .accept(RDFMediaTypes.JSON_LD)
            .get("{podId}", podName)
            .then()
            .statusCode(200)
            .extract().body().asString()
        val pod2 = JsonLdHelper.decode(body2, Pod::class.java)
        assertTrue(JsonObject(pod2.configuration).getJsonObject("auth").containsKey("oidc"));
        assertNull(JsonObject(pod2.configuration).getJsonObject("auth").getJsonObject("oidc"));

        // 2. Removing key, by leaving it out of update object
        // Get current pod config
        val body3 = given()
            .accept(RDFMediaTypes.JSON_LD)
            .get("{podId}", podName)
            .then()
            .statusCode(200)
            .extract().body().asString()
        val pod3 = JsonLdHelper.decode(body3, Pod::class.java)

        // Update oidc config
        val newConfig2 = JsonObject(pod3.configuration)
        newConfig2.getJsonObject("auth").remove("oidc")

        // PUT new config to pod
        given()
            .contentType(RDFMediaTypes.JSON_LD)
            .body(JsonLdHelper.encode(UpdatePodInput(configuration = newConfig2.encode())))
            .put("{podId}", podName)
            .then()
            .statusCode(204)

        // Get current pod config and check change
        val body4 = given()
            .accept(RDFMediaTypes.JSON_LD)
            .get("{podId}", podName)
            .then()
            .statusCode(200)
            .extract().body().asString()
        val pod4 = JsonLdHelper.decode(body4, Pod::class.java)
        assertFalse(JsonObject(pod4.configuration).getJsonObject("auth").containsKey("oidc"));

        // Runtime should be back to normal
        given()
            .accept(MediaType.APPLICATION_JSON)
            .get("{podId}/runtime-config", podName)
            .then()
            .statusCode(200)
            .extract().body().asString().let {
                assertEquals(
                    30,
                    JsonObject(it).getJsonObject("auth").getJsonObject("oidc")
                        .getInteger("jwt-allowed-clock-skew-seconds")
                )
            }

    }
}