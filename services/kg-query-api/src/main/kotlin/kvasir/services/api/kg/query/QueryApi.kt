package kvasir.services.api.kg.query

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.graphql.GraphQLUtils
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.definitions.kg.NamespacePrefixRegistry
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
    private val namespacePrefixRegistry: NamespacePrefixRegistry
) {

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Retrieve data from the KG.",
        description = "Query the knowledge graph of the specified pod using GraphQL."
    )
    fun query(@PathParam("podId") podId: String, input: QueryInputWithContext): Uni<QueryResult> {
        val req = parseInput(podId, input)
        return knowledgeGraph.query(req).map { resp ->
            if (resp.data.containsKey("__schema")) {
                resp.copy(data = resp.data + mapOf("__schema" to resp.data["__schema"]!!.let { schema ->
                    schema as Map<String, Any>
                    schema + listOfNotNull(
                        schema["types"]?.let { types ->
                            "types" to prefixTypeNames(
                                types as List<Map<String, Any>>,
                                input.providedContext ?: namespacePrefixRegistry.getAll(),
                                types.flatMap { (it["fields"] as List<Map<String, Any>>?) ?: emptyList() }
                                    .flatMap { it.keys }.toSet()
                            )
                        }
                    ).toMap()
                }))
            } else {
                resp
            }
        }
    }

    @POST
    @Produces(JSON_LD_MEDIA_TYPE)
    @APIResponse(
        responseCode = "200",
        description = "The query result in JSON-LD format.",
        content = [Content(example = ApiDocConstants.JSON_LD_RESPONSE_EXAMPLE)]
    )
    fun queryJsonLD(@PathParam("podId") podId: String, input: QueryInputWithContext): Uni<Map<String, Any>> {
        val req = parseInput(podId, input)
        return knowledgeGraph.query(req).map {
            // TODO: should we fallback to a default Kvasir context here?
            it.toJsonLD(input.providedContext!!)
        }
    }

    private fun parseInput(podId: String, input: QueryInputWithContext): QueryRequest {
        return QueryRequest(
            podId,
            GraphQLUtils.parseDocumentWithContext(
                input.query,
                input.providedContext ?: namespacePrefixRegistry.getAll()
            ),
            input.variables,
            input.operationName,
            input.targetGraphs
        )
    }

    private fun prefixTypeNames(
        types: List<Map<String, Any>>,
        context: Map<String, Any>,
        fieldKeyProjection: Set<String>
    ): List<Map<String, Any?>> {
        val processedTypes = types.map { type ->
            val fields = (type["fields"] as List<Map<String, Any>>)
            val newFields = fields.map { field ->
                val name = field["name"] as String
                val (prefix, rest) = JsonLdProcessor.compact(mapOf(name to name), context, JsonLdOptions())
                    .filter { it.key != "@context" }.keys.first().split(":")
                field + mapOf("name" to "${prefix}_$rest", "description" to name)
            }
            val name = type["name"] as String
            val (prefix, rest) = JsonLdProcessor.compact(mapOf(name to name), context, JsonLdOptions())
                .filter { it.key != "@context" }.keys.first().split(":")
            type + mapOf(
                "fields" to newFields,
                "name" to "${prefix}_$rest",
                "description" to name
            )
        }
        // Add Query type
        val queryType = mapOf(
            "name" to "Query",
            "kind" to "OBJECT",
            "description" to "Query type",
            "interfaces" to emptyList<Map<String, Any>>(),
            "inputFields" to null,
            "enumValues" to null,
            "possibleTypes" to null,
            "fields" to processedTypes.filter { type -> type["kind"] == "OBJECT" }.map { type ->
                mapOf(
                    "name" to type["name"],
                    "description" to type["description"],
                    "args" to emptyList<Map<String, Any>>(),
                    "type" to mapOf(
                        "kind" to "LIST",
                        "name" to null,
                        "ofType" to mapOf("kind" to "OBJECT", "name" to type["name"])
                    ),
                    "isDeprecated" to false,
                    "deprecationReason" to null
                )//.filterKeys { fieldKeyProjection.contains(it) }

            }
        )
        return processedTypes + queryType
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