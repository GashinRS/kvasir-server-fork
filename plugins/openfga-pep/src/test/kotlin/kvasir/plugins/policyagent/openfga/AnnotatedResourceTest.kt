package kvasir.plugins.policyagent.openfga

import idlab.quarkus.ext.pep.openfga.model.annotations.OpenFgaPolicyEnforcer
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response
import kvasir.utils.test.commons.getTokenForClient
import org.junit.jupiter.api.Test

@QuarkusTest
class AnnotatedResourceTest : AbstractFgaTest() {

    @Test
    fun testAliceCanRead() {
        given()
            .auth().oauth2(getTokenForClient(ALICE_CLIENT_NAME, ALICE_CLIENT_SECRET))
            .get("/$testRunId/annotated-test")
            .then()
            .statusCode(204) // No Content
    }

    @Test
    fun testAliceCanWrite() {
        given()
            .auth().oauth2(getTokenForClient(ALICE_CLIENT_NAME, ALICE_CLIENT_SECRET))
            .post("/$testRunId/annotated-test")
            .then()
            .statusCode(204) // No Content
    }

    @Test
    fun testAliceCanReadChild() {
        given()
            .auth().oauth2(getTokenForClient(ALICE_CLIENT_NAME, ALICE_CLIENT_SECRET))
            .get("/$testRunId/annotated-test/child/123")
            .then()
            .statusCode(204) // No Content
    }

    @Test
    fun testBobCannotRead() {
        given()
            .auth().oauth2(getTokenForClient(BOB_CLIENT_NAME, BOB_CLIENT_SECRET))
            .get("/$testRunId/annotated-test")
            .then()
            .statusCode(401) // Not authorized
    }

    @Test
    fun testAnonymousCannotRead() {
        given()
            .get("/$testRunId/annotated-test")
            .then()
            .statusCode(401) // Not authorized
    }

}

@Path("/{podId}/annotated-test")
class AnnotatedResource {

    @GET
    @OpenFgaPolicyEnforcer
    fun readAction(): Response {
        return Response.noContent().build()
    }

    @POST
    @OpenFgaPolicyEnforcer
    fun writeAction(): Response {
        return Response.noContent().build()
    }

    @GET
    @OpenFgaPolicyEnforcer
    @Path("/child/{childId}")
    fun readChildAction(): Response {
        return Response.noContent().build()
    }

}