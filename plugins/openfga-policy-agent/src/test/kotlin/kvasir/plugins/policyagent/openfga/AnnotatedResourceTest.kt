package kvasir.plugins.policyagent.openfga

import idlab.quarkus.ext.pep.openfga.model.annotations.OpenFgaPolicyEnforcer
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Test

@QuarkusTest
class AnnotatedResourceTest : AbstractFgaTest() {

    @Test
    @TestSecurity(user = "alice")
    fun testAliceCanRead() {
        given()
            .get("/$testRunId/annotated-test")
            .then()
            .statusCode(204) // No Content
    }

    @Test
    @TestSecurity(user = "alice")
    fun testAliceCanWrite() {
        given()
            .post("/$testRunId/annotated-test")
            .then()
            .statusCode(204) // No Content
    }

    @Test
    @TestSecurity(user = "alice")
    fun testAliceCanReadChild() {
        given()
            .get("/$testRunId/annotated-test/child/123")
            .then()
            .statusCode(204) // No Content
    }

    @Test
    @TestSecurity(user = "bob")
    fun testBobCannotRead() {
        given()
            .get("/$testRunId/annotated-test")
            .then()
            .statusCode(403) // Forbidden
    }

    @Test
    fun testAnonymousCannotRead() {
        given()
            .get("/$testRunId/annotated-test")
            .then()
            .statusCode(403) // Forbidden
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