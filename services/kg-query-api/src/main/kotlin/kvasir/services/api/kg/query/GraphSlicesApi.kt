package kvasir.services.api.kg.query

import com.fasterxml.jackson.annotation.JsonProperty
import com.google.common.hash.Hashing
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.ws.rs.*
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

@Tag(name = ApiDocTags.KNOWLEDGE_GRAPH_API)
@Path("{podId}/kg")
class GraphSlicesApi(
    private val sliceStore: SliceStore,
    private val podStore: PodStore,
    private val knowledgeGraph: KnowledgeGraph,
    private val uriInfo: UriInfo,
    @Channel(Channels.SLICE_EVENT_PUBLISH)
    private val sliceEventEmitter: MutinyEmitter<SliceEvent>
) {

    @Path("slices")
    @GET
    @Produces(JSON_LD_MEDIA_TYPE)
    @Operation(
        summary = "List slices of the specified pod.",
        description = "List slices of the specified pod's Knowledge Graph."
    )
    fun listSlices(@PathParam("podId") podId: String): Uni<List<SliceSummary>> {
        return throw404IfPodNotFound(podStore, podId).chain { _ ->
            sliceStore.list(podId)
                .map { slices ->
                    slices.map { slice ->
                        slice.copy(
                            id = uriInfo.absolutePathBuilder.path(slice.id).build().toString()
                        )
                    }
                }
        }
    }

    @Path("slices")
    @POST
    @Consumes(JSON_LD_MEDIA_TYPE)
    @Operation(
        summary = "Define a new slice of the KG.",
        description = "Define a new slice (subset) of the specified pod's Knowledge Graph, based on a GraphQL-LD schema."
    )
    fun createSlice(@PathParam("podId") podId: String, input: SliceInput): Uni<Response> {
        return throw404IfPodNotFound(podStore, podId).chain { _ ->
            // Generate shapes
            val shacl = GraphQL2SHACL(input.schema, input.context).toSHACL()
            val slice = input.toSlice(podId, shacl)
            // Validate the schema
            try {
                SchemaValidator.validateSchema(slice.schema, slice.context)
                sliceStore.persist(slice)
                    .chain { _ -> sliceEventEmitter.send(SliceEvent(podId, slice.id, SliceEventType.CREATED)) }
                    .map {
                        Response.created(uriInfo.absolutePathBuilder.path(slice.id).build()).build()
                    }
            } catch (e: Throwable) {
                Uni.createFrom().failure(e)

            }
        }
    }

    @Path("slices/{sliceId}")
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
        return throw404IfPodNotFound(podStore, podId).chain { _ ->
            sliceStore.getById(podId, sliceId)
                .onItem().ifNull().failWith(NotFoundException("Slice not found"))
                .onItem().ifNotNull().transform { slice ->
                    slice!!.copy(id = uriInfo.absolutePath.toString())
                }
        }
    }

    @Path("slices/{sliceId}")
    @DELETE
    @Operation(
        summary = "Delete a specific slice.",
        description = "Delete a specific slice of the specified pod's Knowledge Graph."
    )
    fun deleteSlice(@PathParam("podId") podId: String, @PathParam("sliceId") sliceId: String): Uni<Response> {
        return throw404IfPodNotFound(podStore, podId).chain { _ ->
            sliceStore.deleteById(podId, sliceId)
                .chain { _ -> sliceEventEmitter.send(SliceEvent(podId, sliceId, SliceEventType.DELETED)) }
                .map { Response.noContent().build() }
        }
    }

    @POST
    @Path("slices/{sliceId}/query")
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
        return throw404IfPodNotFound(podStore, podId).chain { _ ->
            sliceStore.getById(podId, sliceId)
                .onItem().ifNull().failWith(NotFoundException("Slice not found"))
                .onItem().ifNotNull().transformToUni { slice ->
                    executeQuery(podId, slice!!, input)
                }
        }
    }

    @POST
    @Path("slices/{sliceId}/query")
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
        return throw404IfPodNotFound(podStore, podId).chain { _ ->
            sliceStore.getById(podId, sliceId)
                .onItem().ifNull().failWith(NotFoundException("Slice not found"))
                .onItem().ifNotNull().transformToUni { slice ->
                    executeQuery(podId, slice!!, input).map {
                        it.toJsonLD(slice.context)
                    }
                }
        }
    }

    @GET
    @Path("slices/{sliceId}/shacl")
    @Produces(RDFMediaTypes.TURTLE)
    fun getSHACL(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String
    ): Uni<String> {
        return throw404IfPodNotFound(podStore, podId).chain { _ ->
            sliceStore.getById(podId, sliceId)
                .onItem().ifNull().failWith(NotFoundException("Slice not found"))
                .onItem().ifNotNull().transform { slice ->
                    slice!!.shacl
                }
        }
    }

    private fun executeQuery(podId: String, slice: Slice, input: QueryInputImpl): Uni<QueryResult> {
        // Execute the query
        return knowledgeGraph.query(
            QueryRequest(
                slice.context,
                podId,
                input.query,
                input.variables,
                input.operationName,
                slice.targetGraphs,
                slice.schema
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
    fun toSlice(podId: String, shacl: String): Slice {
        return Slice(
            id = Hashing.farmHashFingerprint64().hashString("$podId:$name", Charsets.UTF_8).toString(),
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