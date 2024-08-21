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
import kvasir.definitions.openapi.ApiDocConstants
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.rdf.JSON_LD_MEDIA_TYPE
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.media.Schema
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.tags.Tag

@Tag(name = ApiDocTags.KNOWLEDGE_GRAPH_API)
@Path("{podId}/kg/query")
class QueryApi(
    private val knowledgeGraph: KnowledgeGraph
) {

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Retrieve data from the KG.",
        description = "Query the knowledge graph of the specified pod using GraphQL."
    )
    fun query(@PathParam("podId") podId: String, input: QueryInput): Uni<QueryResult> {
        val req = parseInput(podId, input)
        return knowledgeGraph.query(req)
    }

    @POST
    @Produces(JSON_LD_MEDIA_TYPE)
    @APIResponse(
        responseCode = "200",
        description = "The query result in JSON-LD format.",
        content = [Content(example = ApiDocConstants.JSON_LD_RESPONSE_EXAMPLE)]
    )
    fun queryJsonLD(@PathParam("podId") podId: String, input: QueryInput): Uni<Map<String, Any>> {
        val req = parseInput(podId, input)
        return knowledgeGraph.query(req).map {
            // TODO: should we fallback to a default Kvasir context here?
            it.toJsonLD(input.providedContext!!)
        }
    }

    @POST
    @Path("{virtualKGId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Retrieve data from a specific subset of the KG.",
        description = "Query a virtual Knowledge Graph of the specified pod using GraphQL."
    )
    fun queryVirtual(
        @PathParam("podId") podId: String,
        @PathParam("virtualKGId") @Parameter(description = "Identifier of the virtual Knowledge Graph, representing a subset of the specified pod's Knowledge Graph.") virtualKGId: String,
        input: QueryInput
    ): Uni<QueryResult> {
        TODO()
    }

    @POST
    @Path("{virtualKGId}")
    @Produces(JSON_LD_MEDIA_TYPE)
    @APIResponse(
        responseCode = "200",
        description = "The query result in JSON-LD format.",
        content = [Content(example = ApiDocConstants.JSON_LD_RESPONSE_EXAMPLE)]
    )
    fun queryVirtualJsonLD(@PathParam("podId") podId: String, input: QueryInput): Uni<QueryResult> {
        TODO()
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
    @get:Schema(
        name = "@context",
        description = "The JSON-LD context for the query.",
        example = ApiDocConstants.JSON_LD_CONTEXT_EXAMPLE_2
    )
    val providedContext: Map<String, Any>? = null,
    @get:Schema(
        description = "The GraphQL query string to be executed.",
        example = "{ id ex_givenName(_: \"Bob\") ex_friends { id ex_givenName } }"
    )
    val query: String,
    @get:Schema(
        description = "The name of the operation to be executed (optional, only required if the GraphQL query expresses more than one operation)."
    )
    val operationName: String? = null,
    @get:Schema(
        description = "The variables to be used in the query."
    )
    val variables: Map<String, Any>? = null,
    @get:Schema(
        description = "The named graphs to be targeted by the query. If no graphs are specified, all graphs are targeted."
    )
    val targetGraphs: Set<String> = emptySet()
)