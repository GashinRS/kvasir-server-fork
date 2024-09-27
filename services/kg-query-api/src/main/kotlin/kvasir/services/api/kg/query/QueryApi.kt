package kvasir.services.api.kg.query

import com.fasterxml.jackson.annotation.JsonProperty
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.config.StaticBootstrapConfig
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.kg.QueryResult
import kvasir.definitions.openapi.ApiDocConstants
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.rdf.JSON_LD_MEDIA_TYPE
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.media.Schema
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.tags.Tag

@Tag(name = ApiDocTags.KNOWLEDGE_GRAPH_API)
@Path("{podId}/kg/query")
class QueryApi(
    private val knowledgeGraph: KnowledgeGraph,
    private val podsConfig: StaticBootstrapConfig
) {

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Retrieve data from the KG.",
        description = "Query the knowledge graph of the specified pod using GraphQL."
    )
    fun query(@PathParam("podId") podId: String, input: QueryInputWithContext): Uni<QueryResult> {
        throw404IfPodNotFound(podsConfig, podId)
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
    fun queryJsonLD(@PathParam("podId") podId: String, input: QueryInputWithContext): Uni<Map<String, Any>> {
        throw404IfPodNotFound(podsConfig, podId)
        val req = parseInput(podId, input)
        return knowledgeGraph.query(req).map {
            it.toJsonLD(req.context)
        }
    }

    private fun parseInput(podId: String, input: QueryInputWithContext): QueryRequest {
        return QueryRequest(
            getDefaultContextFor(podId),
            podId,
            input.query,
            input.variables,
            input.operationName,
            input.targetGraphs
        )
    }

    private fun getDefaultContextFor(podId: String): Map<String, Any> {
        val pod = podsConfig.pods().first { it.name() == podId }
        return pod.defaultPrefixes()
    }
}

interface QueryInput {


    @get:Schema(
        description = "The GraphQL query string to be executed.",
        example = "{ id ex_givenName(_: \"Bob\") ex_friends { id ex_givenName } }"
    )
    val query: String

    @get:Schema(
        description = "The name of the operation to be executed (optional, only required if the GraphQL query expresses more than one operation)."
    )
    val operationName: String?

    @get:Schema(
        description = "The variables to be used in the query."
    )
    val variables: Map<String, Any>?

    @get:Schema(
        description = "The named graphs to be targeted by the query. If no graphs are specified, all graphs are targeted."
    )
    val targetGraphs: Set<String>
}

data class QueryInputImpl(
    override val query: String,
    override val operationName: String? = null,
    override val variables: Map<String, Any>? = null,
    override val targetGraphs: Set<String> = emptySet()
) : QueryInput

data class QueryInputWithContext(
    override val query: String,
    override val operationName: String? = null,
    override val variables: Map<String, Any>? = null,
    override val targetGraphs: Set<String> = emptySet(),
    @get:JsonProperty("@context")
    @get:Schema(
        name = "@context",
        description = "The JSON-LD context for the query.",
        example = ApiDocConstants.JSON_LD_CONTEXT_EXAMPLE_2
    )
    val providedContext: Map<String, Any>? = null
) : QueryInput

internal fun throw404IfPodNotFound(podConfig: StaticBootstrapConfig, podId: String) {
    if (podConfig.pods().none { it.name() == podId }) {
        throw NotFoundException("Pod not found: $podId")
    }
}