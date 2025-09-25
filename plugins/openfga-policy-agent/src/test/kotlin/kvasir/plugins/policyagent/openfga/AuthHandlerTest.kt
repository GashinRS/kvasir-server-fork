package kvasir.plugins.policyagent.openfga

import io.quarkiverse.openfga.client.model.RelObject
import io.quarkiverse.openfga.client.model.RelTupleDefinition
import io.quarkiverse.openfga.client.model.RelUser
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.vertx.ext.web.Router
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import kvasir.definitions.auth.AuthHandler
import kvasir.plugins.policyagent.openfga.utils.contextualizeSubject
import org.junit.jupiter.api.Test

/**
 * Tests the Kvasir OpenFGA plugin setup when an AuthHandler is used.
 */
@QuarkusTest
class AuthHandlerTest : AbstractFgaTest() {

    @Test
    @TestSecurity(user = "alice")
    fun testAliceCanAccessPod() {
        given()
            .get("/$testRunId/")
            .then()
            .statusCode(204)
    }

    @Test
    @TestSecurity(user = "alice")
    fun testAliceCanAccessChildResource() {
        given()
            .get("/$testRunId/router-test/a/b/c")
            .then()
            .statusCode(204)
    }

    @Test
    @TestSecurity(user = "bob")
    fun testBobCannotAccessPod() {
        given()
            .get("/$testRunId/router-test/")
            .then()
            .statusCode(403) // Forbidden
    }

    @Test
    @TestSecurity(user = "bob")
    fun testBobCanAccessExplicitlyAllowedResource() {
        // Grant access
        fgaManager.addTuples(
            testRunId, setOf(
                RelTupleDefinition.builder().user(RelUser.of("user", contextualizeSubject("bob"))).relation("reader")
                    .`object`(RelObject.of("resource", "/$testRunId/router-test/allowed-resource")).build()
            )
        ).await().indefinitely()
        given()
            .get("/$testRunId/router-test/allowed-resource")
            .then()
            .statusCode(204) // No Content
    }

    @Test
    fun testAnonymousUserCannotAccessPod() {
        given()
            .get("/$testRunId/")
            .then()
            .statusCode(403) // Forbidden
    }

    @Test
    fun testAnonymousUserCanAccessExplicitlyAllowedResource() {
        // Grant access to anonymous users
        fgaManager.addTuples(
            testRunId, setOf(
                RelTupleDefinition.builder().user(RelUser.of("user", contextualizeSubject("anonymous")))
                    .relation("reader")
                    .`object`(RelObject.of("resource", "/$testRunId/router-test/public-resource")).build()
            )
        ).await().indefinitely()
        given()
            .get("/$testRunId/router-test/public-resource")
            .then()
            .statusCode(204) // No Content
    }

}

@ApplicationScoped
class RouterBasedApi(
    private val authHandler: AuthHandler
) {
    fun onStart(@Observes router: Router) {
        // Expose root handler
        router.route("/:podId/")
            .handler(authHandler)
            .handler { ctx ->
                // Return a 204 no content
                ctx.response().setStatusCode(204).end()
            }
        router.route("/:podId/router-test/*")
            .handler(authHandler)
            .handler { ctx ->
                // Return a 204 no content
                ctx.response().setStatusCode(204).end()
            }
    }
}