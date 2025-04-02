package kvasir.services.api.kg.query

import com.fasterxml.jackson.annotation.JsonProperty
import graphql.language.Document
import graphql.parser.Parser
import graphql.parser.antlr.GraphqlParser
import io.quarkus.security.PermissionsAllowed
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.sse.OutboundSseEvent
import jakarta.ws.rs.sse.Sse
import kvasir.definitions.kg.*
import kvasir.definitions.kg.graphql.TYPE_MUTATION
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.kg.slices.SliceStore
import kvasir.definitions.kg.slices.SliceSummary
import kvasir.definitions.messaging.Channels
import kvasir.definitions.openapi.ApiDocConstants
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.rdf.JSON_LD_MEDIA_TYPE
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import kvasir.utils.graphql.SchemaValidator
import kvasir.utils.shacl.GraphQL2SHACL
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.eclipse.microprofile.reactive.messaging.Channel
import org.jboss.resteasy.reactive.RestStreamElementType
import java.net.URI
import kotlin.jvm.optionals.getOrNull

@Path("")
class GraphSlicesApi(
    private val sliceStore: SliceStore,
    private val podStore: PodStore,
    private val knowledgeGraph: KnowledgeGraph,
    private val uriInfo: UriInfo,
    private val securityIdentity: SecurityIdentity,
    private val sse: Sse,
    @Channel(Channels.LIFECYCLE_EVENTS_PUBLISH)
    private val lifeCycleEventEmitter: MutinyEmitter<LifeCycleEvent>
) {

    @Tag(name = ApiDocTags.PODS_API)
    @Path("{podId}/slices")
    @GET
    @Produces(JSON_LD_MEDIA_TYPE)
    @Operation(
        summary = "List slices of the specified pod.",
        description = "List slices of the specified pod's Knowledge Graph."
    )
    fun listSlices(@PathParam("podId") podId: String): Uni<List<SliceSummary>> {
        val fqPodId = uriInfo.absolutePath.toString().substringBefore("/slices")
        return getPodOrThrow404(podStore, fqPodId).chain { _ ->
            sliceStore.list(fqPodId)
        }
    }

    @Tag(name = ApiDocTags.PODS_API)
    @Path("{podId}/slices")
    @POST
    @Consumes(JSON_LD_MEDIA_TYPE)
    @Operation(
        summary = "Define a new slice of the KG.",
        description = "Define a new slice (subset) of the specified pod's Knowledge Graph, based on a GraphQL-LD schema."
    )
    fun createSlice(@PathParam("podId") podId: String, input: SliceInput): Uni<Response> {
        val fqPodId = uriInfo.absolutePath.toString().substringBefore("/slices")
        val fqSliceId = uriInfo.absolutePathBuilder.path(input.name).build().toString()
        return getPodOrThrow404(podStore, fqPodId)
            .chain { _ ->
                // A Slice with the same name should not exist
                sliceStore.getById(fqPodId, fqSliceId)
                    .onItem().ifNotNull().failWith(ClientErrorException(Response.Status.CONFLICT))
                    .onItem().ifNull().switchTo { validateAndPersistSlice(fqPodId, fqSliceId, input) }
            }
            .chain { _ ->
                // Emit life-cycle event
                lifeCycleEventEmitter.send(
                    LifeCycleEvent(
                        type = LifeCycleEventType.SLICE_CREATED,
                        podId = fqPodId,
                        sliceId = fqSliceId
                    )
                )
            }
            .map { _ ->
                Response.created(URI.create(fqSliceId)).build()
            }
    }

    @Tag(name = ApiDocTags.PODS_API)
    @Path("{podId}/slices/{sliceId}")
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
        val fqPodId = uriInfo.absolutePath.toString().substringBefore("/slices")
        val fqSliceId = uriInfo.absolutePath.toString()
        return getSliceOrThrow404(sliceStore, fqPodId, fqSliceId)
    }

    @Tag(name = ApiDocTags.PODS_API)
    @Path("{podId}/slices/{sliceId}")
    @PUT
    @Consumes(JSON_LD_MEDIA_TYPE)
    @Operation(
        summary = "Update a specific slice definition..",
        description = "Update a specific slice definition details."
    )
    fun updateSlice(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        input: SliceInput,
    ): Uni<Response> {
        val fqPodId = uriInfo.absolutePath.toString().substringBefore("/slices")
        val fqSliceId = uriInfo.absolutePath.toString()
        return getSliceOrThrow404(sliceStore, fqPodId, fqSliceId).chain { _ ->
            validateAndPersistSlice(fqPodId, fqSliceId, input)
                .chain { _ ->
                    // Emit life-cycle event
                    lifeCycleEventEmitter.send(
                        LifeCycleEvent(
                            type = LifeCycleEventType.SLICE_UPDATED,
                            podId = fqPodId,
                            sliceId = fqSliceId
                        )
                    )
                }
                .map { _ -> Response.noContent().build() }
        }
    }

    @Tag(name = ApiDocTags.PODS_API)
    @Path("{podId}/slices/{sliceId}")
    @DELETE
    @Operation(
        summary = "Delete a specific slice.",
        description = "Delete a specific slice of the specified pod's Knowledge Graph."
    )
    fun deleteSlice(@PathParam("podId") podId: String, @PathParam("sliceId") sliceId: String): Uni<Response> {
        val fqPodId = uriInfo.absolutePath.toString().substringBefore("/slices")
        val fqSliceId = uriInfo.absolutePath.toString()
        return getSliceOrThrow404(sliceStore, fqPodId, fqSliceId).chain { _ ->
            sliceStore.deleteById(fqPodId, fqSliceId)
                .chain { _ ->
                    // Emit life-cycle event
                    lifeCycleEventEmitter.send(
                        LifeCycleEvent(
                            type = LifeCycleEventType.SLICE_DELETED,
                            podId = fqPodId,
                            sliceId = fqSliceId
                        )
                    )
                }
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
    ): Uni<QueryResult> {
        val fqPodId = uriInfo.absolutePath.toString().substringBefore("/slices")
        val fqSliceId = uriInfo.absolutePath.toString().substringBefore("/query")
        return getSliceOrThrow404(sliceStore, fqPodId, fqSliceId).chain { slice ->
            executeQuery(fqPodId, slice, input).toUni()
        }
    }

    @POST
    @Path("{podId}/slices/{sliceId}/query")
    @Consumes(MediaType.APPLICATION_JSON)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Retrieve data from a specific subset of the KG.",
        description = "Query a predefined slice of the specified pod's Knowledge Graph using GraphQL."
    )
    fun streamVirtual(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") @Parameter(description = "Identifier of the Knowledge Graph slice, representing a subset of the specified pod's Knowledge Graph.") sliceId: String,
        input: QueryInputImpl,
    ): Multi<OutboundSseEvent> {
        val fqPodId = uriInfo.absolutePath.toString().substringBefore("/slices")
        val fqSliceId = uriInfo.absolutePath.toString().substringBefore("/query")
        return getSliceOrThrow404(sliceStore, fqPodId, fqSliceId).onItem().transformToMulti { slice ->
            executeQuery(fqPodId, slice, input).map { sse.newEventBuilder().name("next").data(it).build() }
        }
    }

    @Tag(name = ApiDocTags.KG_QUERYING_API)
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
    ): Uni<Any> {
        val fqPodId = uriInfo.absolutePath.toString().substringBefore("/slices")
        val fqSliceId = uriInfo.absolutePath.toString().substringBefore("/query")
        return getSliceOrThrow404(sliceStore, fqPodId, fqSliceId).chain { slice ->
            executeQuery(fqPodId, slice, input).toUni().map {
                it.toJsonLD(slice.context)
            }
        }
    }

    private fun executeQuery(
        podId: String,
        slice: Slice,
        input: QueryInputImpl
    ): Multi<QueryResult> {
        // Parse query document
        val queryDoc = Parser.parse(input.query)

        // Execute the query
        return knowledgeGraph.query(
            QueryRequest(
                slice.context,
                podId,
                slice.id,
                input.query,
                input.variables,
                input.operationName,
                slice.schema,
                input.atTimestamp,
                input.atChangeRequest
            )
        )
    }

    private fun validateAndPersistSlice(podId: String, sliceId: String, input: SliceInput): Uni<Slice> {
        return try {
            // Generate shapes
            val shaclConvertor = GraphQL2SHACL(input.schema, input.context)
            val slice = input.toSlice(podId, sliceId, shaclConvertor.hasMutations())
            // Validate the schema
            SchemaValidator.validateSchema(slice.schema, slice.context)
            sliceStore.persist(slice).map { slice }
        } catch (err: Throwable) {
            Uni.createFrom().failure(err)
        }
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
    fun toSlice(podId: String, sliceId: String, supportsChanges: Boolean): Slice {
        return Slice(
            id = sliceId,
            context = context,
            podId = podId,
            name = name,
            description = description,
            schema = schema,
            supportsChanges = supportsChanges,
            targetGraphs = targetGraphs
        )
    }
}