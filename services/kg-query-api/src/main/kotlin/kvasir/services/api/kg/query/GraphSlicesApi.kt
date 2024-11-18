package kvasir.services.api.kg.query

import com.fasterxml.jackson.annotation.JsonProperty
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import kvasir.definitions.kg.*
import kvasir.definitions.messaging.Channels
import kvasir.definitions.openapi.ApiDocConstants
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.rdf.JSON_LD_MEDIA_TYPE
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.utils.graphql2shacl.GraphQL2SHACL
import kvasir.utils.kg.SchemaValidator
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.eclipse.microprofile.reactive.messaging.Channel
import java.net.URI
import java.time.Instant
import java.util.Optional
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

@Tag(name = ApiDocTags.KNOWLEDGE_GRAPH_API)
@Path("")
class GraphSlicesApi(
    private val sliceStore: SliceStore,
    private val podStore: PodStore,
    private val knowledgeGraph: KnowledgeGraph,
    private val uriInfo: UriInfo,
    @Channel(Channels.SLICE_EVENT_PUBLISH)
    private val sliceEventEmitter: MutinyEmitter<SliceEvent>
) {

    @Path("{podId}/slices")
    @GET
    @Produces(JSON_LD_MEDIA_TYPE)
    @Operation(
        summary = "List slices of the specified pod.",
        description = "List slices of the specified pod's Knowledge Graph."
    )
    fun listSlices(@PathParam("podId") podId: String): Uni<List<SliceSummary>> {
        val podId = uriInfo.absolutePath.toString().substringBefore("/slices")
        return throw404IfPodNotFound(podStore, podId).chain { _ ->
            sliceStore.list(podId)
        }
    }

    @Path("{podId}/slices")
    @POST
    @Consumes(JSON_LD_MEDIA_TYPE)
    @Operation(
        summary = "Define a new slice of the KG.",
        description = "Define a new slice (subset) of the specified pod's Knowledge Graph, based on a GraphQL-LD schema."
    )
    fun createSlice(@PathParam("podId") podId: String, input: SliceInput, @Context uriInfo: UriInfo): Uni<Response> {
        val podId = uriInfo.absolutePath.toString().substringBefore("/slices")
        return throw404IfPodNotFound(podStore, podId).chain { _ ->
            // Generate shapes
            val shacl = GraphQL2SHACL(input.schema, input.context).toSHACL()
            val slice = input.toSlice(podId, shacl, uriInfo)
            // Validate the schema
            try {
                SchemaValidator.validateSchema(slice.schema, slice.context)
                sliceStore.persist(slice)
                    .chain { _ -> sliceEventEmitter.send(SliceEvent(podId, slice.id, SliceEventType.CREATED)) }
                    .map {
                        Response.created(URI.create(slice.id)).build()
                    }
            } catch (e: Throwable) {
                Uni.createFrom().failure(e)

            }
        }
    }

    @Path("{podId}/slices/{sliceId}")
    @GET
    @Produces(JSON_LD_MEDIA_TYPE)
    @Operation(
        summary = "Retrieve a specific slice definition..",
        description = "Retrieve a specific slice definition details."
    )
    fun getSlice(
        @PathParam("podId") podId: String
    ): Uni<Slice> {
        val podId = uriInfo.absolutePath.toString().substringBefore("/slices")
        return throw404IfPodNotFound(podStore, podId).chain { _ ->
            sliceStore.getById(podId, uriInfo.absolutePath.toString())
                .onItem().ifNull().failWith(NotFoundException("Slice not found"))
        }
    }

    @Path("{podId}/slices/{sliceId}")
    @DELETE
    @Operation(
        summary = "Delete a specific slice.",
        description = "Delete a specific slice of the specified pod's Knowledge Graph."
    )
    fun deleteSlice(@PathParam("podId") podId: String): Uni<Response> {
        val podId = uriInfo.absolutePath.toString().substringBefore("/slices")
        val sliceId = uriInfo.absolutePath.toString()
        return throw404IfPodNotFound(podStore, podId).chain { _ ->
            sliceStore.deleteById(podId, sliceId)
                .chain { _ -> sliceEventEmitter.send(SliceEvent(podId, sliceId, SliceEventType.DELETED)) }
                .map { Response.noContent().build() }
        }
    }

    @POST
    @Path("{podId}/slices/{sliceId}/query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Retrieve data from a specific subset of the KG.",
        description = "Query a predefined slice of the specified pod's Knowledge Graph using GraphQL."
    )
    fun queryVirtual(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") @Parameter(description = "Identifier of the Knowledge Graph slice, representing a subset of the specified pod's Knowledge Graph.") sliceId: String,
        input: QueryInputImpl,
        @QueryParam("atTimestamp") @Parameter(
            description = "Query the state of the KG at the specified point in time.",
            required = false
        ) atTimestamp: Optional<Instant>,
        @QueryParam("atChangeRequestId") @Parameter(
            description = "Query the state of the KG when the specified change request was applied.",
            required = false
        ) atChangeRequestId: Optional<String>
    ): Uni<QueryResult> {
        val podId = uriInfo.absolutePath.toString().substringBefore("/slices")
        val sliceId = uriInfo.absolutePath.toString()
        return throw404IfPodNotFound(podStore, podId).chain { _ ->
            sliceStore.getById(podId, sliceId)
                .onItem().ifNull().failWith(NotFoundException("Slice not found"))
                .onItem().ifNotNull().transformToUni { slice ->
                    executeQuery(podId, slice!!, input, atTimestamp.getOrNull(), atChangeRequestId.getOrNull())
                }
        }
    }

    @POST
    @Path("{podId}/slices/{sliceId}/query")
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
        input: QueryInputImpl,
        @QueryParam("atTimestamp") @Parameter(
            description = "Query the state of the KG at the specified point in time.",
            required = false
        ) atTimestamp: Optional<Instant>,
        @QueryParam("atChangeRequestId") @Parameter(
            description = "Query the state of the KG when the specified change request was applied.",
            required = false
        ) atChangeRequestId: Optional<String>
    ): Uni<Map<String, Any>> {
        val podId = uriInfo.absolutePath.toString().substringBefore("/slices")
        val sliceId = uriInfo.absolutePath.toString()
        return throw404IfPodNotFound(podStore, podId).chain { _ ->
            sliceStore.getById(podId, sliceId)
                .onItem().ifNull().failWith(NotFoundException("Slice not found"))
                .onItem().ifNotNull().transformToUni { slice ->
                    executeQuery(podId, slice!!, input, atTimestamp.getOrNull(), atChangeRequestId.getOrNull()).map {
                        it.toJsonLD(slice.context)
                    }
                }
        }
    }

    @GET
    @Path("{podId}/slices/{sliceId}/shacl")
    @Produces(RDFMediaTypes.TURTLE)
    fun getSHACL(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String
    ): Uni<String> {
        val podId = uriInfo.absolutePath.toString().substringBefore("/slices")
        val sliceId = uriInfo.absolutePath.toString()
        return throw404IfPodNotFound(podStore, podId).chain { _ ->
            sliceStore.getById(podId, sliceId)
                .onItem().ifNull().failWith(NotFoundException("Slice not found"))
                .onItem().ifNotNull().transform { slice ->
                    slice!!.shacl
                }
        }
    }

    private fun executeQuery(
        podId: String,
        slice: Slice,
        input: QueryInputImpl,
        atTimestamp: Instant? = null,
        atChangeRequestId: String? = null
    ): Uni<QueryResult> {
        // Execute the query
        return knowledgeGraph.query(
            QueryRequest(
                slice.context,
                podId,
                input.query,
                input.variables,
                input.operationName,
                slice.targetGraphs,
                slice.schema,
                atTimestamp,
                atChangeRequestId
            )
        )
    }
}

data class SliceInput(
    @JsonProperty(JsonLdKeywords.context)
    val context: Map<String, Any>,
    @JsonProperty(KvasirVocab.name)
    val name: String,
    @JsonProperty(KvasirVocab.schema)
    val schema: String,
    @JsonProperty(KvasirVocab.description)
    val description: String = "",
    @JsonProperty(KvasirVocab.targetGraphs)
    val targetGraphs: Set<String> = emptySet()
) {
    fun toSlice(podId: String, shacl: String, uriInfo: UriInfo): Slice {
        return Slice(
            id = uriInfo.absolutePathBuilder.path(UUID.randomUUID().toString()).build().toString(),
            context = context,
            podId = podId,
            name = name,
            description = description,
            schema = schema,
            shacl = shacl,
            targetGraphs = targetGraphs
        )
    }
}