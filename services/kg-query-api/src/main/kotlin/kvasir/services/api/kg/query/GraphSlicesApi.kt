package kvasir.services.api.kg.query

import com.fasterxml.jackson.annotation.JsonProperty
import com.google.common.hash.Hashing
import graphql.parser.Parser
import idlab.quarkus.ext.pep.openfga.model.annotations.OpenFgaPolicyEnforcer
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import io.vertx.core.json.JsonObject
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.sse.OutboundSseEvent
import jakarta.ws.rs.sse.Sse
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.kg.*
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.kg.slices.SliceStoreFactory
import kvasir.definitions.kg.slices.SliceSummary
import kvasir.definitions.openapi.ApiDocConstants
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.rdf.JSON_LD_MEDIA_TYPE
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import kvasir.plugins.messaging.kafka.Channels
import kvasir.plugins.http.common.extensions.openfga.extractors.GraphQLGetRelationExtractor
import kvasir.plugins.http.common.extensions.openfga.extractors.GraphQLPostRelationExtractor
import kvasir.utils.graphql.SliceGraphQLSchema
import kvasir.utils.http.KvasirUriInfo
import kvasir.utils.http.getChildUri
import kvasir.utils.http.getParentUri
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.responses.APIResponseSchema
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.eclipse.microprofile.reactive.messaging.Channel
import org.jboss.resteasy.reactive.RestStreamElementType
import java.net.URI
import java.util.*
import kotlin.jvm.optionals.getOrNull

@Path("")
class GraphSlicesApi(
    private val sliceStoreFactory: SliceStoreFactory,
    private val podStoreFactory: PodStoreFactory,
    private val knowledgeGraph: KnowledgeGraph,
    private val uriInfo: KvasirUriInfo,
    private val sse: Sse,
    @Channel(Channels.LIFECYCLE_EVENTS_PUBLISH)
    private val lifeCycleEventEmitter: MutinyEmitter<LifeCycleEvent>,
    private val securityIdentity: Instance<SecurityIdentity>,
    @ConfigProperty(name = "kvasir.auth.anonymous-user-name", defaultValue = "anonymous")
    private val anonymousUserName: String
) {

    @Tag(name = ApiDocTags.PODS_API)
    @Path("{podId}/slices")
    @GET
    @Produces(JSON_LD_MEDIA_TYPE)
    @Operation(
        summary = "List slices of the specified pod.",
        description = "List slices of the specified pod's Knowledge Graph."
    )
    @APIResponseSchema(SliceGraph::class)
    @OpenFgaPolicyEnforcer
    fun listSlices(@PathParam("podId") podId: String): Uni<List<SliceSummary>> {
        val fqPodId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return getPodOrThrow404(podStoreFactory.createPodStore(), fqPodId).chain { _ ->
            sliceStoreFactory.getSliceStore(fqPodId).find()
                .map { results -> results.items.map { SliceSummary(it.id, it.name, it.description) } }
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
    @APIResponse(responseCode = "201", description = "Slice successfully created.")
    @OpenFgaPolicyEnforcer
    fun createSlice(@PathParam("podId") podId: String, input: SliceInput): Uni<Response> {
        val fqPodId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getChildUri(input.name).toASCIIString()
        return getPodOrThrow404(podStoreFactory.createPodStore(), fqPodId)
            .chain { _ ->
                // A Slice with the same name should not exist
                sliceStoreFactory.getSliceStore(fqPodId).findById(fqSliceId)
                    .onItem().ifNotNull().failWith(ClientErrorException(Response.Status.CONFLICT))
                    .onItem().ifNull().switchTo { validateAndPersistSlice(fqPodId, fqSliceId, input) }
            }
            .chain { _ ->
                // Emit life-cycle event
                lifeCycleEventEmitter.send(
                    LifeCycleEvent(
                        type = LifeCycleEventType.SLICE_CREATED,
                        requestingUser = securityIdentity.takeIf { it.isResolvable }?.get()?.principal?.name
                            ?: anonymousUserName,
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
    @Path("{podId}/slices")
    @Produces("text/plain")
    @PUT
    @Operation(
        summary = "Generate SDL preview of Slice schema",
        description = "Generates a SDL preview of the given Slice schema."
    )
    @OpenFgaPolicyEnforcer
    fun previewSliceSDL(input: SliceInput): Uni<String> {
        val parsedSchema = SliceGraphQLSchema(input.schema, input.context)
        return Uni.createFrom().item(parsedSchema.getSDL());
    }

    @Tag(name = ApiDocTags.PODS_API)
    @Path("{podId}/slices/{sliceId}")
    @GET
    @Produces(JSON_LD_MEDIA_TYPE)
    @Operation(
        summary = "Retrieve a specific slice definition..",
        description = "Retrieve a specific slice definition details."
    )
    @OpenFgaPolicyEnforcer
    fun getSlice(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String
    ): Uni<Slice> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(2).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().toASCIIString()
        return getSliceOrThrow404(sliceStoreFactory.getSliceStore(fqPodId), fqPodId, fqSliceId)
    }

    @Tag(name = ApiDocTags.PODS_API)
    @Path("{podId}/slices/{sliceId}")
    @PUT
    @Consumes(JSON_LD_MEDIA_TYPE)
    @Operation(
        summary = "Update a specific slice definition..",
        description = "Update a specific slice definition details."
    )
    @APIResponse(responseCode = "204", description = "Slice successfully updated.")
    @OpenFgaPolicyEnforcer
    fun updateSlice(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        input: SliceInput,
    ): Uni<Response> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(2).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().toASCIIString()
        return getSliceOrThrow404(sliceStoreFactory.getSliceStore(fqPodId), fqPodId, fqSliceId).chain { _ ->
            validateAndPersistSlice(fqPodId, fqSliceId, input)
                .chain { _ ->
                    // Emit life-cycle event
                    lifeCycleEventEmitter.send(
                        LifeCycleEvent(
                            type = LifeCycleEventType.SLICE_UPDATED,
                            podId = fqPodId,
                            requestingUser = securityIdentity.takeIf { it.isResolvable }?.get()?.principal?.name
                                ?: anonymousUserName,
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
    @APIResponse(responseCode = "201", description = "Slice successfully deleted.")
    @OpenFgaPolicyEnforcer
    fun deleteSlice(@PathParam("podId") podId: String, @PathParam("sliceId") sliceId: String): Uni<Response> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(2).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().toASCIIString()
        val sliceStore = sliceStoreFactory.getSliceStore(fqPodId)
        return getSliceOrThrow404(sliceStore, fqPodId, fqSliceId).chain { _ ->
            sliceStore.deleteById(fqSliceId)
                .chain { _ ->
                    // Emit life-cycle event
                    lifeCycleEventEmitter.send(
                        LifeCycleEvent(
                            type = LifeCycleEventType.SLICE_DELETED,
                            requestingUser = securityIdentity.takeIf { it.isResolvable }?.get()?.principal?.name
                                ?: anonymousUserName,
                            podId = fqPodId,
                            sliceId = fqSliceId
                        )
                    )
                }
                .map { Response.noContent().build() }
        }
    }

    @Tag(name = ApiDocTags.KG_QUERYING_API)
    @POST
    @Path("{podId}/slices/{sliceId}/query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Interact with a specific subset of the KG.",
        description = "Execute a query on a predefined slice of the specified pod's Knowledge Graph using GraphQL."
    )
    @OpenFgaPolicyEnforcer(relation = GraphQLPostRelationExtractor::class, readBody = true)
    fun queryVirtual(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") @Parameter(description = "Identifier of the Knowledge Graph slice, representing a subset of the specified pod's Knowledge Graph.") sliceId: String,
        input: QueryInputImpl,
    ): Uni<QueryResult> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(3).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return getSliceOrThrow404(sliceStoreFactory.getSliceStore(fqPodId), fqPodId, fqSliceId).chain { slice ->
            executeQuery(fqPodId, slice, input).toUni()
        }
    }

    @POST
    @Path("{podId}/slices/{sliceId}/query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @OpenFgaPolicyEnforcer(relation = GraphQLPostRelationExtractor::class, readBody = true)
    fun streamVirtual(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") @Parameter(description = "Identifier of the Knowledge Graph slice, representing a subset of the specified pod's Knowledge Graph.") sliceId: String,
        input: QueryInputImpl,
    ): Multi<OutboundSseEvent> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(3).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return getSliceOrThrow404(sliceStoreFactory.getSliceStore(fqPodId), fqPodId, fqSliceId).onItem()
            .transformToMulti { slice ->
                executeQuery(fqPodId, slice, input).map { sse.newEventBuilder().name("next").data(it).build() }
            }
    }

    /**
     * A GET variant of the query endpoint for Subscriptions is provided for compatibility with SSE clients that
     * only support GET requests.
     */
    @GET
    @Path("{podId}/slices/{sliceId}/query")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @OpenFgaPolicyEnforcer(relation = GraphQLGetRelationExtractor::class)
    fun streamVirtualViaGet(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        @QueryParam("query") query: String,
        @QueryParam("variables") variables: Optional<String>,
        @QueryParam("operationName") operationName: Optional<String>,
    ): Multi<OutboundSseEvent> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(3).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        val queryInputImpl =
            QueryInputImpl(
                query = query,
                variables = variables.getOrNull()?.let { JsonObject(it).map },
                operationName = operationName.getOrNull()
            )
        return getSliceOrThrow404(sliceStoreFactory.getSliceStore(fqPodId), fqPodId, fqSliceId).onItem()
            .transformToMulti { slice ->
                executeQuery(fqPodId, slice, queryInputImpl).map {
                    sse.newEventBuilder().name("next").data(it).build()
                }
            }
    }

    @POST
    @Path("{podId}/slices/{sliceId}/query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(JSON_LD_MEDIA_TYPE)
    @APIResponse(
        responseCode = "200",
        content = [Content(example = ApiDocConstants.JSON_LD_RESPONSE_EXAMPLE)]
    )
    @OpenFgaPolicyEnforcer(relation = GraphQLPostRelationExtractor::class, readBody = true)
    fun queryVirtualJsonLD(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        input: QueryInputImpl,
    ): Uni<Any> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(3).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return getSliceOrThrow404(sliceStoreFactory.getSliceStore(fqPodId), fqPodId, fqSliceId).chain { slice ->
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
                securityIdentity.takeIf { it.isResolvable }?.get()?.principal?.name ?: anonymousUserName,
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
            // Check if the schema contains mutations
            val parsedSchema = SliceGraphQLSchema(input.schema, input.context)
            val slice = input.toSlice(
                securityIdentity.takeIf { it.isResolvable }?.get()?.principal?.name ?: anonymousUserName,
                sliceId,
                parsedSchema.hasMutations()
            )
            // Validate the schema
            parsedSchema.validate()
            sliceStoreFactory.getSliceStore(podId).persist(slice).map { slice }
        } catch (err: Throwable) {
            Uni.createFrom().failure(err)
        }
    }
}

@GenerateNoArgConstructor
data class SliceInput(
    @get:JsonProperty(JsonLdKeywords.context)
    val context: Map<String, Any>,
    @get:JsonProperty(KvasirVocab.name)
    val name: String = Hashing.farmHashFingerprint64().hashString(UUID.randomUUID().toString(), Charsets.UTF_8)
        .toString(),
    @get:JsonProperty(KvasirVocab.schema)
    val schema: String,
    @get:JsonProperty(KvasirVocab.description)
    val description: String = "",
    @get:JsonProperty(KvasirVocab.targetGraphs)
    val targetGraphs: Set<String> = emptySet()
) {
    fun toSlice(principal: String, sliceId: String, supportsChanges: Boolean): Slice {
        return Slice(
            id = sliceId,
            context = context,
            author = principal,
            name = name,
            description = description,
            schema = schema,
            supportsChanges = supportsChanges,
            targetGraphs = targetGraphs
        )
    }
}

// Only needed for OpenAPI documentation
@GenerateNoArgConstructor
data class SliceGraph(
    @get:JsonProperty(JsonLdKeywords.graph)
    val graph: List<Slice>
)