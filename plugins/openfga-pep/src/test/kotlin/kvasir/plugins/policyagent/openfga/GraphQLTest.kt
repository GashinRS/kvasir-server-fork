package kvasir.plugins.policyagent.openfga

import dev.openfga.sdk.api.client.model.ClientTupleKey
import dev.openfga.sdk.errors.FgaApiValidationError
import idlab.quarkus.ext.pep.openfga.model.annotations.OpenFgaPolicyEnforcer
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.*
import kvasir.definitions.kg.QueryResult
import kvasir.plugins.http.common.extensions.openfga.extractors.GraphQLGetRelationExtractor
import kvasir.plugins.http.common.extensions.openfga.extractors.GraphQLPostRelationExtractor
import kvasir.plugins.policyagent.openfga.utils.contextualizeSubject
import kvasir.utils.test.commons.getTokenForClient
import org.junit.jupiter.api.Test

@QuarkusTest
class GraphQLTest : AbstractFgaTest() {

    @Test
    fun testAliceCanExecuteQuery() {
        given()
            .auth().oauth2(getTokenForClient(ALICE_CLIENT_NAME, ALICE_CLIENT_SECRET))
            .body(QueryInputImpl(query = "{ someQuery }"))
            .contentType("application/json")
            .post("/$testRunId/graphql")
            .then()
            .statusCode(200)
    }

    @Test
    fun testAliceCanExecuteMutation() {
        given()
            .auth().oauth2(getTokenForClient(ALICE_CLIENT_NAME, ALICE_CLIENT_SECRET))
            .body(QueryInputImpl(query = "mutation { someMutation }"))
            .contentType("application/json")
            .post("/$testRunId/graphql")
            .then()
            .statusCode(200)
    }

    @Test
    fun testAliceCanExecuteQueryViaGet() {
        given()
            .auth().oauth2(getTokenForClient(ALICE_CLIENT_NAME, ALICE_CLIENT_SECRET))
            .queryParam("query", "{ someQuery }")
            .get("/$testRunId/graphql")
            .then()
            .statusCode(200)
    }

    @Test
    fun testAliceCanExecuteMutationViaGet() {
        given()
            .auth().oauth2(getTokenForClient(ALICE_CLIENT_NAME, ALICE_CLIENT_SECRET))
            .queryParam("query", "mutation { someMutation }")
            .get("/$testRunId/graphql")
            .then()
            .statusCode(200)
    }

    @Test
    fun testBobIsRestrictedToQuery() {
        // Grant access
        fgaManager.addTuples(
            testRunId, listOf(
                ClientTupleKey()
                    .user("user:${contextualizeSubject("service-account-${BOB_CLIENT_NAME}")}")
                    .relation("reader")
                    ._object("resource:/$testRunId/graphql")
            )
        ).onFailure(FgaApiValidationError::class.java).recoverWithUni(Uni.createFrom().voidItem()).await()
            .indefinitely()

        // Bob should be able to execute queries
        given()
            .auth().oauth2(getTokenForClient(BOB_CLIENT_NAME, BOB_CLIENT_SECRET))
            .queryParam("query", "{ someQuery }")
            .get("/$testRunId/graphql")
            .then()
            .statusCode(200)

        // Bob should not be able to execute mutations
        given()
            .auth().oauth2(getTokenForClient(BOB_CLIENT_NAME, BOB_CLIENT_SECRET))
            .queryParam("query", "mutation { someMutation }")
            .get("/$testRunId/graphql")
            .then()
            .statusCode(401) // Not authorized
    }

    @Test
    fun testBobIsRestrictedToQueryViaGet() {
        // Grant access
        fgaManager.addTuples(
            testRunId, listOf(
                ClientTupleKey().user("user:${contextualizeSubject("service-account-${BOB_CLIENT_NAME}")}")
                    .relation("reader")
                    ._object("resource:/$testRunId/graphql")
            )
        ).onFailure(FgaApiValidationError::class.java).recoverWithUni(Uni.createFrom().voidItem()).await()
            .indefinitely()

        // Bob should be able to execute queries
        given()
            .auth().oauth2(getTokenForClient(BOB_CLIENT_NAME, BOB_CLIENT_SECRET))
            .body(QueryInputImpl(query = "{ someQuery }"))
            .contentType("application/json")
            .post("/$testRunId/graphql")
            .then()
            .statusCode(200)

        // Bob should not be able to execute mutations
        given()
            .auth().oauth2(getTokenForClient(BOB_CLIENT_NAME, BOB_CLIENT_SECRET))
            .body(QueryInputImpl(query = "mutation { someMutation }"))
            .contentType("application/json")
            .post("/$testRunId/graphql")
            .then()
            .statusCode(401) // Not authorized
    }

    @Test
    fun testAnonymousCannotExecuteQuery() {
        // Anonymous users should not be able to execute queries
        given()
            .body(QueryInputImpl(query = "{ someQuery }"))
            .contentType("application/json")
            .post("/$testRunId/graphql")
            .then()
            .statusCode(401) // Not authorized
    }

}

@Path("/{podId}/graphql")
class GraphQLResource {

    @POST
    @OpenFgaPolicyEnforcer(relation = GraphQLPostRelationExtractor::class, readBody = true)
    fun query(
        @PathParam("podId") podId: String,
        input: QueryInputImpl
    ): QueryResult {
        return QueryResult()
    }

    @GET
    @OpenFgaPolicyEnforcer(relation = GraphQLGetRelationExtractor::class)
    fun queryViaGet(
        @PathParam("podId") podId: String,
        @QueryParam("query") query: String,
        @QueryParam("operationName") operationName: String? = null
    ): QueryResult {
        return QueryResult()
    }

}

data class QueryInputImpl(
    val query: String,
    val operationName: String? = null,
    val variables: Map<String, Any>? = null,
)