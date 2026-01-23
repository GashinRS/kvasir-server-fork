package kvasir.plugins.policyagent.openfga

import dev.openfga.sdk.api.client.model.ClientTupleKey
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.vertx.ext.web.Router
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import kvasir.definitions.auth.AuthHandler
import kvasir.plugins.policyagent.openfga.utils.contextualizeSubject
import kvasir.utils.test.commons.getTokenForClient
import org.junit.jupiter.api.Test

/**
 * Tests the Kvasir OpenFGA plugin setup when an AuthHandler is used.
 */
@QuarkusTest
class AuthHandlerTest : AbstractFgaTest() {

    @Test
    fun testAliceCanAccessPod() {
        given()
            .auth().oauth2(getTokenForClient(ALICE_CLIENT_NAME, ALICE_CLIENT_SECRET))
            .get("/$testRunId/")
            .then()
            .statusCode(204)
    }

    @Test
    fun testAliceCanAccessChildResource() {
        given()
            .auth().oauth2(getTokenForClient(ALICE_CLIENT_NAME, ALICE_CLIENT_SECRET))
            .get("/$testRunId/router-test/a/b/c")
            .then()
            .statusCode(204)
    }

    @Test
    fun testBobCannotAccessPod() {
        given()
            .auth().oauth2(getTokenForClient(BOB_CLIENT_NAME, BOB_CLIENT_SECRET))
            .get("/$testRunId/router-test/")
            .then()
            .statusCode(401) // Not authorized
    }

    @Test
    fun testBobCanAccessExplicitlyAllowedResource() {
        // Grant access
        fgaManager.addTuples(
            testRunId, listOf(
                ClientTupleKey().user("user:${contextualizeSubject("service-account-${BOB_CLIENT_NAME}")}")
                    .relation("reader")
                    ._object("resource:/$testRunId/router-test/allowed-resource")
            )
        ).await().indefinitely()
        given()
            .auth().oauth2(getTokenForClient(BOB_CLIENT_NAME, BOB_CLIENT_SECRET))
            .get("/$testRunId/router-test/allowed-resource")
            .then()
            .statusCode(204) // No Content
    }

    @Test
    fun testAnonymousUserCannotAccessPod() {
        given()
            .get("/$testRunId/")
            .then()
            .statusCode(401) // Not authorized
    }

    @Test
    fun testAnonymousUserCanAccessExplicitlyAllowedResource() {
        // Grant access to anonymous users
        fgaManager.addTuples(
            testRunId, listOf(
                ClientTupleKey().user("user:${contextualizeSubject("anonymous")}")
                    .relation("reader")
                    ._object("resource:/$testRunId/router-test/public-resource")
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