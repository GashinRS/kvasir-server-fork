package kvasir.services.api.kg.query

import com.google.common.hash.Hashing
import graphql.ExecutionInput
import graphql.ParseAndValidate
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kvasir.definitions.config.StaticBootstrapConfig
import kvasir.definitions.graphql.GraphQLUtils
import kvasir.definitions.kg.*
import kvasir.definitions.openapi.ApiDocConstants
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.rdf.JSON_LD_MEDIA_TYPE
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.tags.Tag

@Tag(name = ApiDocTags.KNOWLEDGE_GRAPH_API)
@Path("{podId}/kg/slices")
class GraphSlicesApi(private val sliceStore: SliceStore, private val knowledgeGraph: KnowledgeGraph, private val podConfig: StaticBootstrapConfig) {

    @GET
    @Produces(JSON_LD_MEDIA_TYPE)
    @Operation(
        summary = "List slices of the specified pod.",
        description = "List slices of the specified pod's Knowledge Graph."
    )
    fun listSlices(@PathParam("podId") podId: String): Uni<List<SliceSummary>> {
        throw404IfPodNotFound(podConfig, podId)
        return sliceStore.list(podId)
    }

    @POST
    @Consumes(JSON_LD_MEDIA_TYPE)
    @Operation(
        summary = "Define a new slice of the KG.",
        description = "Define a new slice (subset) of the specified pod's Knowledge Graph, based on a GraphQL-LD schema."
    )
    fun createSlice(@PathParam("podId") podId: String, input: SliceInput): Uni<Response> {
        throw404IfPodNotFound(podConfig, podId)
        val slice = input.toSlice(podId)
        // Validate the schema
        GraphQLUtils.parseDocumentWithContext(slice.spec, emptyMap())
        return sliceStore.persist(slice).map {
            Response.status(Response.Status.CREATED).entity(slice).build()
        }
    }

    @Path("{sliceId}")
    @GET
    @Produces(JSON_LD_MEDIA_TYPE)
    @Operation(
        summary = "Retrieve a specific slice definition..",
        description = "Retrieve a specific slice definition details."
    )
    fun getSlice(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String
    ): Uni<Slice> {
        throw404IfPodNotFound(podConfig, podId)
        return sliceStore.getById(sliceId)
    }

    @Path("{sliceId}")
    @DELETE
    @Operation(
        summary = "Delete a specific slice.",
        description = "Delete a specific slice of the specified pod's Knowledge Graph."
    )
    fun deleteSlice(@PathParam("podId") podId: String, @PathParam("sliceId") sliceId: String): Uni<Response> {
        throw404IfPodNotFound(podConfig, podId)
        return sliceStore.deleteById(sliceId).map { Response.noContent().build() }
    }

    @POST
    @Path("{sliceId}/query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Retrieve data from a specific subset of the KG.",
        description = "Query a predefined slice of the specified pod's Knowledge Graph using GraphQL."
    )
    fun queryVirtual(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") @Parameter(description = "Identifier of the Knowledge Graph slice, representing a subset of the specified pod's Knowledge Graph.") sliceId: String,
        input: QueryInputImpl
    ): Uni<QueryResult> {
        throw404IfPodNotFound(podConfig, podId)
        return sliceStore.getById(sliceId).chain { slice ->
            executeQuery(podId, slice, input)
        }
    }

    @POST
    @Path("{sliceId}/query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(JSON_LD_MEDIA_TYPE)
    @APIResponse(
        responseCode = "200",
        description = "The query result in JSON-LD format.",
        content = [Content(example = ApiDocConstants.JSON_LD_RESPONSE_EXAMPLE)]
    )
    fun queryVirtualJsonLD(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        input: QueryInputImpl
    ): Uni<Map<String, Any>> {
        throw404IfPodNotFound(podConfig, podId)
        return sliceStore.getById(sliceId).chain { slice ->
            executeQuery(podId, slice, input).map {
                it.toJsonLD(slice.context)
            }
        }
    }

    private fun executeQuery(podId: String, slice: Slice, input: QueryInputImpl): Uni<QueryResult> {
        val typeDefRegistry = SchemaParser().parse(slice.spec)
        val schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(typeDefRegistry)
        val queryInput = ExecutionInput.newExecutionInput(input.query).build()
        val validationResult = ParseAndValidate.parseAndValidate(schema, queryInput)
        return if (validationResult.isFailure) {
            throw BadRequestException("Invalid query: ${validationResult.errors}")
        } else {
            // Execute the query
            knowledgeGraph.query(
                QueryRequest(
                    podId,
                    GraphQLUtils.parseDocumentWithContext(input.query, slice.context),
                    input.variables,
                    input.operationName,
                    slice.targetGraphs
                )
            )
        }
    }

}

data class SliceInput(
    val context: Map<String, Any>,
    val name: String,
    val schema: String,
    val description: String = "",
    val targetGraphs: Set<String> = emptySet()
) {
    fun toSlice(podId: String): Slice {
        return Slice(
            id = Hashing.farmHashFingerprint64().hashString("$podId:$name", Charsets.UTF_8).toString(),
            context = context,
            podId = podId,
            name = name,
            description = description,
            spec = schema,
            targetGraphs = targetGraphs
        )
    }
}