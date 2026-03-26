package kvasir.services.api.pods

import com.fasterxml.jackson.annotation.JsonProperty
import com.google.common.hash.Hashing
import idlab.quarkus.ext.pep.openfga.model.annotations.OpenFgaPolicyEnforcer
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Response
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.kg.LifeCycleEvent
import kvasir.definitions.kg.LifeCycleEventType
import kvasir.definitions.kg.Pod
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.kg.slices.SliceSummary
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.persistence.Repository
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.plugins.messaging.kafka.Channels
import kvasir.utils.graphql.SliceGraphQLSchema
import kvasir.utils.http.KvasirUriInfo
import kvasir.utils.http.getChildUri
import kvasir.utils.http.getParentUri
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.responses.APIResponseSchema
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.eclipse.microprofile.reactive.messaging.Channel
import java.net.URI
import java.util.*

@Path("")
class SliceManagementApi(
    private val uriInfo: KvasirUriInfo,
    private val repositoryFactory: RepositoryFactory,
    @Channel(Channels.LIFECYCLE_EVENTS_PUBLISH)
    private val lifeCycleEventEmitter: MutinyEmitter<LifeCycleEvent>,
    private val securityIdentity: Instance<SecurityIdentity>,
    @ConfigProperty(name = "kvasir.auth.anonymous-user-name", defaultValue = "anonymous")
    private val anonymousUserName: String
) {

    @Tag(name = ApiDocTags.PODS_API)
    @Path("{podId}/slices")
    @GET
    @Produces(RDFMediaTypes.JSON_LD)
    @Operation(
        summary = "List slices of the specified pod.",
        description = "List slices of the specified pod's Knowledge Graph."
    )
    @APIResponseSchema(SliceGraph::class)
    @OpenFgaPolicyEnforcer
    fun listSlices(@PathParam("podId") podId: String): Uni<List<SliceSummary>> {
        val fqPodId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return getPodOrThrow404(repositoryFactory.getRepository(Pod::class), fqPodId).chain { _ ->
            repositoryFactory.getRepository(Slice::class, fqPodId).find()
                .map { results -> results.items.map { SliceSummary(it.id, it.name, it.description) } }
        }
    }

    @Tag(name = ApiDocTags.PODS_API)
    @Path("{podId}/slices")
    @POST
    @Consumes(RDFMediaTypes.JSON_LD)
    @Operation(
        summary = "Define a new slice of the KG.",
        description = "Define a new slice (subset) of the specified pod's Knowledge Graph, based on a GraphQL-LD schema."
    )
    @APIResponse(responseCode = "201", description = "Slice successfully created.")
    @OpenFgaPolicyEnforcer
    fun createSlice(@PathParam("podId") podId: String, input: SliceInput): Uni<Response> {
        val fqPodId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getChildUri(input.name).toASCIIString()
        return getPodOrThrow404(repositoryFactory.getRepository(Pod::class), fqPodId)
            .chain { _ ->
                // A Slice with the same name should not exist
                repositoryFactory.getRepository(Slice::class, fqPodId).findById(fqSliceId)
                    .onItem().ifNotNull().failWith(ClientErrorException(Response.Status.CONFLICT))
                    .onItem().ifNull().switchTo { validateAndPersistSlice(fqPodId, fqSliceId, input) }
            }
            .chain { _ ->
                // Emit life-cycle event
                lifeCycleEventEmitter.send(
                    LifeCycleEvent(
                        eventType = LifeCycleEventType.SLICE_CREATED,
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
    @Produces(RDFMediaTypes.JSON_LD)
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
        return getSliceOrThrow404(repositoryFactory.getRepository(Slice::class, fqPodId), fqPodId, fqSliceId)
    }

    @Tag(name = ApiDocTags.PODS_API)
    @Path("{podId}/slices/{sliceId}")
    @PUT
    @Consumes(RDFMediaTypes.JSON_LD)
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
        return getSliceOrThrow404(
            repositoryFactory.getRepository(Slice::class, fqPodId),
            fqPodId,
            fqSliceId
        ).chain { _ ->
            validateAndPersistSlice(fqPodId, fqSliceId, input)
                .chain { _ ->
                    // Emit life-cycle event
                    lifeCycleEventEmitter.send(
                        LifeCycleEvent(
                            eventType = LifeCycleEventType.SLICE_UPDATED,
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
        val sliceStore = repositoryFactory.getRepository(Slice::class, fqPodId)
        return getSliceOrThrow404(sliceStore, fqPodId, fqSliceId).chain { _ ->
            sliceStore.deleteById(fqSliceId)
                .chain { _ ->
                    // Emit life-cycle event
                    lifeCycleEventEmitter.send(
                        LifeCycleEvent(
                            eventType = LifeCycleEventType.SLICE_DELETED,
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
            repositoryFactory.getRepository(Slice::class, podId).persist(slice).map { slice }
        } catch (err: Throwable) {
            Uni.createFrom().failure(err)
        }
    }

}

@GenerateNoArgConstructor
data class SliceInput(
    val context: Map<String, Any>,
    val name: String = Hashing.farmHashFingerprint64().hashString(UUID.randomUUID().toString(), Charsets.UTF_8)
        .toString(),
    val schema: String,
    val description: String = "",
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

internal fun getPodOrThrow404(podStore: Repository<Pod>, podId: String): Uni<Pod> {
    return podStore.findById(podId)
        .onItem().ifNull().failWith(NotFoundException("Pod not found: $podId"))
        .onItem().ifNotNull().transform { it!! }
}

internal fun getSliceOrThrow404(sliceStore: Repository<Slice>, podId: String, sliceId: String): Uni<Slice> {
    return sliceStore.findById(sliceId)
        .onItem().ifNull().failWith(NotFoundException("Slice not found: $sliceId"))
        .onItem().ifNotNull().transform { it!! }
}