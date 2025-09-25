package kvasir.plugins.policyagent.openfga

import idlab.quarkus.ext.pep.openfga.model.annotations.OpenFgaPolicyEnforcer
import io.quarkiverse.openfga.client.model.FGAValidationException
import io.quarkiverse.openfga.client.model.RelObject
import io.quarkiverse.openfga.client.model.RelTupleDefinition
import io.quarkiverse.openfga.client.model.RelUser
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import kvasir.definitions.kg.QueryResult
import kvasir.plugins.policyagent.openfga.extractors.GraphQLGetRelationExtractor
import kvasir.plugins.policyagent.openfga.extractors.GraphQLPostRelationExtractor
import kvasir.plugins.policyagent.openfga.utils.contextualizeSubject
import org.junit.jupiter.api.Test

@QuarkusTest
class GraphQLTest : AbstractFgaTest() {

    @Test
    @TestSecurity(user = "alice")
    fun testAliceCanExecuteQuery() {
        given()
            .body(QueryInputImpl(query = "{ someQuery }"))
            .contentType("application/json")
            .post("/$testRunId/graphql")
            .then()
            .statusCode(200)
    }

    @Test
    @TestSecurity(user = "alice")
    fun testAliceCanExecuteMutation() {
        given()
            .body(QueryInputImpl(query = "mutation { someMutation }"))
            .contentType("application/json")
            .post("/$testRunId/graphql")
            .then()
            .statusCode(200)
    }

    @Test
    @TestSecurity(user = "alice")
    fun testAliceCanExecuteQueryViaGet() {
        given()
            .queryParam("query", "{ someQuery }")
            .get("/$testRunId/graphql")
            .then()
            .statusCode(200)
    }

    @Test
    @TestSecurity(user = "alice")
    fun testAliceCanExecuteMutationViaGet() {
        given()
            .queryParam("query", "mutation { someMutation }")
            .get("/$testRunId/graphql")
            .then()
            .statusCode(200)
    }

    @Test
    @TestSecurity(user = "bob")
    fun testBobIsRestrictedToQuery() {
        // Grant access
        fgaManager.addTuples(
            testRunId, setOf(
                RelTupleDefinition.builder().user(RelUser.of("user", contextualizeSubject("bob"))).relation("reader")
                    .`object`(RelObject.of("resource", "/$testRunId/graphql")).build()
            )
        ).onFailure(FGAValidationException::class.java).recoverWithUni(Uni.createFrom().voidItem()).await()
            .indefinitely()

        // Bob should be able to execute queries
        given()
            .queryParam("query", "{ someQuery }")
            .get("/$testRunId/graphql")
            .then()
            .statusCode(200)

        // Bob should not be able to execute mutations
        given()
            .queryParam("query", "mutation { someMutation }")
            .get("/$testRunId/graphql")
            .then()
            .statusCode(403) // Forbidden
    }

    @Test
    @TestSecurity(user = "bob")
    fun testBobIsRestrictedToQueryViaGet() {
        // Grant access
        fgaManager.addTuples(
            testRunId, setOf(
                RelTupleDefinition.builder().user(RelUser.of("user", contextualizeSubject("bob"))).relation("reader")
                    .`object`(RelObject.of("resource", "/$testRunId/graphql")).build()
            )
        ).onFailure(FGAValidationException::class.java).recoverWithUni(Uni.createFrom().voidItem()).await()
            .indefinitely()

        // Bob should be able to execute queries
        given()
            .body(QueryInputImpl(query = "{ someQuery }"))
            .contentType("application/json")
            .post("/$testRunId/graphql")
            .then()
            .statusCode(200)

        // Bob should not be able to execute mutations
        given()
            .body(QueryInputImpl(query = "mutation { someMutation }"))
            .contentType("application/json")
            .post("/$testRunId/graphql")
            .then()
            .statusCode(403) // Forbidden
    }

    @Test
    fun testAnonymousCannotExecuteQuery() {
        // Anonymous users should not be able to execute queries
        given()
            .body(QueryInputImpl(query = "{ someQuery }"))
            .contentType("application/json")
            .post("/$testRunId/graphql")
            .then()
            .statusCode(403) // Forbidden
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