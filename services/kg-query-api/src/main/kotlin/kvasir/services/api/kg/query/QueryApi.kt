package kvasir.services.api.kg.query

import com.fasterxml.jackson.annotation.JsonProperty
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.graphql.QueryUtils
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.kg.QueryResult

@Path("{podId}/kg/query")
class QueryApi(
    private val knowledgeGraph: KnowledgeGraph
) {

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    fun query(@PathParam("podId") podId: String, input: QueryInput): Uni<QueryResult> {
        val req = parseInput(podId, input)
        return knowledgeGraph.query(req)
    }

    @POST
    @Produces("application/json+ld")
    fun queryJsonLD(@PathParam("podId") podId: String, input: QueryInput): Uni<Map<String, Any>> {
        val req = parseInput(podId, input)
        return knowledgeGraph.query(req).map {
            // TODO: should we fallback to a default Kvasir context here?
            it.toJsonLD(input.providedContext!!)
        }
    }

    private fun parseInput(podId: String, input: QueryInput): QueryRequest {
        return QueryRequest(
            podId,
            QueryUtils.parseQueryWithContext(input.query, input.providedContext ?: emptyMap()),
            input.variables,
            input.operationName,
            input.targetGraphs
        )
    }

}

data class QueryInput(
    @JsonProperty("@context")
    val providedContext: Map<String, Any>? = null,
    val query: String,
    val operationName: String? = null,
    val variables: Map<String, Any>? = null,
    val targetGraphs: Set<String> = emptySet()
)