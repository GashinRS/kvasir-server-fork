package kvasir.services.api.pods

import com.fasterxml.jackson.annotation.JsonFormat
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
import kvasir.definitions.auth.AuthConstants
import kvasir.definitions.kg.LifeCycleEvent
import kvasir.definitions.kg.LifeCycleEventType
import kvasir.definitions.kg.Pod
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.kg.slices.SliceSchema
import kvasir.definitions.kg.slices.SliceSummary
import kvasir.definitions.kg.slices.tryReadingEmbeddedSDL
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.persistence.EntityTag
import kvasir.definitions.persistence.Repository
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.persistence.VersionedRepository
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.plugins.messaging.kafka.Channels
import kvasir.utils.graphql.SliceGraphQLSchema
import kvasir.utils.http.KvasirUriInfo
import kvasir.utils.http.getChildUri
import kvasir.utils.http.getParentUri
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.responses.APIResponseSchema
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.eclipse.microprofile.reactive.messaging.Channel
import org.jboss.resteasy.reactive.RestResponse
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder
import java.net.URI
import java.time.Instant
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
        val repository = repositoryFactory.getVersionedRepository(Slice::class, fqPodId)
        return getPodOrThrow404(repositoryFactory.getRepository(Pod::class), fqPodId).chain { _ ->
            repository.find()
                .chain { results ->
                    repository.getLineage(results.items).map { results to it }
                }
                .map { (results, lineage) ->
                    results.items.map {
                        SliceSummary(
                            it.id,
                            it.name,
                            it.description,
                            lineage[it]
                        )
                    }
                }
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
                repositoryFactory.getVersionedRepository(Slice::class, fqPodId).findById(fqSliceId)
                    .onItem().ifNotNull().failWith(ClientErrorException(Response.Status.CONFLICT))
                    .onItem().ifNull().switchTo { validateAndPersistSlice(fqPodId, fqSliceId, input) }
            }
            .chain { _ ->
                // Emit life-cycle event
                lifeCycleEventEmitter.send(
                    LifeCycleEvent(
                        eventType = LifeCycleEventType.SLICE_CREATED,
                        requestingUser = getPrincipal(),
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
        val parsedSchema = SliceGraphQLSchema(input.schema.tryReadingEmbeddedSDL(), input.context)
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
        return getSliceOrThrow404(repositoryFactory.getVersionedRepository(Slice::class, fqPodId), fqPodId, fqSliceId)
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
            repositoryFactory.getVersionedRepository(Slice::class, fqPodId),
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
                            requestingUser = getPrincipal(),
                            sliceId = fqSliceId
                        )
                    )
                }
                .map { _ -> Response.noContent().build() }
        }
    }

    @Tag(name = ApiDocTags.PODS_API)
    @Path("{podId}/slices/{sliceId}/tags")
    @GET
    @Produces(RDFMediaTypes.JSON_LD)
    @Operation(
        summary = "List tags of the specified slice.",
        description = "Returns a paginated list of tagged versions for the specified slice."
    )
    @OpenFgaPolicyEnforcer
    fun listSliceTags(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        @QueryParam("pageSize") @Parameter(required = false) @DefaultValue("100") pageSize: Int,
        @QueryParam("cursor") @Parameter(required = false) cursor: Optional<String>
    ): Uni<RestResponse<List<EntityTag>>> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(3).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri(1).toASCIIString()
        val sliceStore = repositoryFactory.getVersionedRepository(Slice::class, fqPodId)
        return getSliceOrThrow404(sliceStore, fqPodId, fqSliceId)
            .chain { _ ->
                sliceStore.listTags(fqSliceId, pageSize, cursor.orElse(null))
            }
            .map { result ->
                ResponseBuilder.ok(result.items.map {
                    it.id = uriInfo.getResourceUri().getChildUri(it.tag).toASCIIString()
                    it
                })
                    .apply {
                        result.nextCursor?.let { this.link(uriInfo.getAbsoluteUri("cursor" to it), "next") }
                        result.previousCursor?.let { this.link(uriInfo.getAbsoluteUri("cursor" to it), "previous") }
                    }
                    .build()
            }
    }

    @Tag(name = ApiDocTags.PODS_API)
    @Path("{podId}/slices/{sliceId}/tags/{tag}")
    @DELETE
    @Operation(
        summary = "Delete a specific tag of a slice.",
        description = "Deletes the specified tag for the specified Slice, making the tagged version no longer accessible via that tag."
    )
    @APIResponse(responseCode = "204", description = "Tag successfully deleted.")
    @OpenFgaPolicyEnforcer
    fun deleteSliceTag(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        @PathParam("tag") tag: String
    ): Uni<Response> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(4).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri(2).toASCIIString()
        val sliceStore = repositoryFactory.getVersionedRepository(Slice::class, fqPodId)
        return getSliceOrThrow404(sliceStore, fqPodId, fqSliceId)
            .chain { _ ->
                sliceStore.removeTag(fqSliceId, tag)
            }
            .map { Response.noContent().build() }
    }

    @Tag(name = ApiDocTags.PODS_API)
    @Path("{podId}/slices/{sliceId}/tags/{tag}")
    @PUT
    @Consumes(RDFMediaTypes.JSON_LD)
    @Operation(
        summary = "Update a tagged slice revision.",
        description = """
            Creates a new revision on the tag's own branch without touching the main branch head,
            then moves the tag pointer to the new revision.
            This allows evolving a tagged version (e.g. a release branch) independently of the
            main development timeline.
        """
    )
    @APIResponse(responseCode = "204", description = "Tag successfully updated.")
    @OpenFgaPolicyEnforcer
    fun updateSliceTag(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        @PathParam("tag") tag: String,
        input: SliceInput
    ): Uni<Response> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(4).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri(2).toASCIIString()
        val sliceStore = repositoryFactory.getVersionedRepository(Slice::class, fqPodId)
        return getSliceOrThrow404(sliceStore, fqPodId, fqSliceId, tag)
            .chain { currentTaggedSlice ->
                validateAndPersistSlice(
                    fqPodId, fqSliceId, input,
                    FromRevision(tag, currentTaggedSlice.revisionId!!)
                )
            }
            .map { Response.noContent().build() }
    }

    @Tag(name = ApiDocTags.PODS_API)
    @Path("{podId}/slices/{sliceId}/tags/{sourceTag}/alias/{aliasTag}")
    @PUT
    @Operation(
        summary = "Alias a tagged slice revision.",
        description = """
            Creates an additional tag (alias) that points to the same revision as the source tag.
            Useful for e.g. pointing a 'latest' or 'stable' tag at an already-tagged release.
        """
    )
    @APIResponse(responseCode = "204", description = "Alias successfully created.")
    @OpenFgaPolicyEnforcer
    fun aliasSliceTag(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        @PathParam("sourceTag") sourceTag: String,
        @PathParam("aliasTag") aliasTag: String
    ): Uni<Response> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(6).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri(4).toASCIIString()
        val sliceStore = repositoryFactory.getVersionedRepository(Slice::class, fqPodId)
        return getSliceOrThrow404(sliceStore, fqPodId, fqSliceId, sourceTag)
            .chain { sourceSlice ->
                sliceStore.addTag(
                    fqSliceId,
                    EntityTag(
                        revisionId = sourceSlice.revisionId!!,
                        tag = aliasTag,
                        createdAt = Instant.now(),
                        createdBy = getPrincipal()
                    )
                )
            }
            .map { Response.noContent().build() }
    }

    @Tag(name = ApiDocTags.PODS_API)
    @Path("{podId}/slices/{sliceId}/tags/{tag}")
    @GET
    @Produces(RDFMediaTypes.JSON_LD)
    @Operation(
        summary = "Retrieve a slice at a specific tag.",
        description = "Retrieves the Slice definition as JSON-LD for the version associated with the specified tag."
    )
    @OpenFgaPolicyEnforcer
    fun getSliceAtTag(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        @PathParam("tag") tag: String
    ): Uni<Slice> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(4).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri(2).toASCIIString()
        return getSliceOrThrow404(
            repositoryFactory.getVersionedRepository(Slice::class, fqPodId),
            fqPodId,
            fqSliceId,
            tag
        )
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
        val sliceStore = repositoryFactory.getVersionedRepository(Slice::class, fqPodId)
        return getSliceOrThrow404(sliceStore, fqPodId, fqSliceId).chain { _ ->
            sliceStore.deleteById(fqSliceId)
                .chain { _ ->
                    // Emit life-cycle event
                    lifeCycleEventEmitter.send(
                        LifeCycleEvent(
                            eventType = LifeCycleEventType.SLICE_DELETED,
                            requestingUser = getPrincipal(),
                            podId = fqPodId,
                            sliceId = fqSliceId
                        )
                    )
                }
                .map { Response.noContent().build() }
        }
    }

    private fun validateAndPersistSlice(
        podId: String,
        sliceId: String,
        input: SliceInput,
        fromRevision: FromRevision? = null
    ): Uni<Slice> {
        return try {
            val parsedSchema = SliceGraphQLSchema(input.schema.tryReadingEmbeddedSDL(), input.context)
            val slice = input.toSlice(getPrincipal(), sliceId, parsedSchema.hasMutations()).also {
                it.branch = fromRevision?.tag
                it.parentRevisionId = fromRevision?.revisionId
            }
            parsedSchema.validate()
            repositoryFactory.getVersionedRepository(Slice::class, podId)
                .run {
                    when {
                        // Tags were explicitly specified
                        input.tags != null -> this.persist(slice, input.tags)
                        // A fromRevision is supplied: by default we override the tag that is branched off from
                        fromRevision != null -> this.persist(slice, setOf(fromRevision.tag))
                        // Else persist the slice without tags
                        else -> this.persist(slice)
                    }
                }.map { slice }
        } catch (err: Throwable) {
            Uni.createFrom().failure(err)
        }
    }

    private fun getPrincipal(): String {
        return securityIdentity.takeIf { it.isResolvable }?.get()?.principal?.name
            ?: AuthConstants.ANONYMOUS_USERNAME
    }

}

@GenerateNoArgConstructor
data class SliceInput(
    val context: Map<String, Any>,
    val name: String = Hashing.farmHashFingerprint64().hashString(UUID.randomUUID().toString(), Charsets.UTF_8)
        .toString(),
    val schema: SliceSchema,
    val description: String = "",
    @get:JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    val tags: Set<String>? = null
) {
    fun toSlice(principal: String, sliceId: String, supportsChanges: Boolean): Slice {
        return Slice(
            id = sliceId,
            context = context,
            createdBy = principal,
            name = name,
            description = description,
            schema = schema,
            supportsChanges = supportsChanges
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

internal fun getSliceOrThrow404(
    sliceStore: VersionedRepository<Slice>,
    podId: String,
    sliceId: String,
    tag: String? = null
): Uni<Slice> {
    return sliceStore.run {
        // If a tag is specified, lookup the matching revision. Else: use the latest revision on the main branch.
        tag?.let { this.findById(sliceId, it) } ?: this.findById(sliceId)
    }
        .onItem().ifNull().failWith(
            NotFoundException(if (tag != null) "Slice tag not found: $tag" else "Slice not found: $sliceId")
        )
        .onItem().ifNotNull().transform { it!! }
}

private data class FromRevision(val tag: String, val revisionId: String)