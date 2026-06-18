package kvasir.services.api.kg.changes

import idlab.quarkus.ext.pep.openfga.model.annotations.OpenFgaPolicyEnforcer
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Response
import kvasir.definitions.auth.AuthConstants
import kvasir.definitions.kg.ChangeRecordRequest
import kvasir.definitions.kg.ChangeRecords
import kvasir.definitions.kg.changes.ProcessedChange
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.persistence.Sort
import kvasir.definitions.persistence.SortOrder
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.utils.http.getParentUri
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.responses.APIResponseSchema
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.jboss.resteasy.reactive.RestResponse
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder
import java.util.*

@Tag(name = ApiDocTags.KG_CHANGES_API)
@Path("{podId}/slices/{sliceId}/tags/{tag}/changes")
class TaggedSliceChangesApi : AbstractChangesApi() {

    // URI layout: /{podId}/slices/{sliceId}/tags/{tag}/changes
    //   fqPodId  = getParentUri(5)  → removes changes, {tag}, tags, {sliceId}, slices
    //   fqSliceId = getParentUri(3) → removes changes, {tag}, tags

    @POST
    @Consumes(RDFMediaTypes.JSON_LD)
    @Operation(
        summary = "Perform mutations validated against a specific tagged version of a KG slice.",
        description = "Post a change request to a slice inbox, validated against the schema at the specified tag."
    )
    @APIResponse(responseCode = "201", description = "Change request created.")
    @OpenFgaPolicyEnforcer
    fun processTaggedSliceChangeRequest(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        @PathParam("tag") tag: String,
        @QueryParam("sync") @Parameter(
            description = "When `true`, block until the change has been fully processed and return the resulting " +
                    "ProcessedChange report (HTTP 200). Defaults to `false` (async, HTTP 201 + Location).",
            required = false
        ) @DefaultValue("false") sync: Boolean,
        input: ChangeRequestInput
    ): Uni<Response> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(5).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri(3).toASCIIString()
        return repositoryFactory.getVersionedRepository(Slice::class, fqPodId).findById(fqSliceId, tag)
            .onItem().ifNull().failWith(NotFoundException("Slice tag not found: $tag"))
            .onItem().ifNotNull().transformToUni { slice ->
                if (slice!!.supportsChanges) {
                    val changeCommand = input.toChangeRequest(
                        fqPodId,
                        securityIdentity.takeIf { it.isResolvable }?.get()?.principal?.name
                            ?: AuthConstants.ANONYMOUS_USERNAME,
                        fqSliceId,
                        tag
                    )
                    handlePublishChange(fqPodId, changeCommand, sync)
                } else {
                    Uni.createFrom().item(Response.status(Response.Status.METHOD_NOT_ALLOWED).build())
                }
            }
    }

    // URI layout: /{podId}/slices/{sliceId}/tags/{tag}/changes/pending/{requestId}
    //   fqPodId  = getParentUri(7) → removes {requestId}, pending, changes, {tag}, tags, {sliceId}, slices
    //   fqSliceId = getParentUri(5) → removes {requestId}, pending, changes, {tag}, tags

    @Path("/pending/{requestId}")
    @GET
    @Produces(RDFMediaTypes.JSON_LD)
    fun getPendingTaggedChangeRequest(
        @PathParam("tag") tag: String,
        @PathParam("requestId") requestId: String
    ): Uni<Response> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(7).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri(5).toASCIIString()
        return handlePendingChangeRequest(fqPodId, uriInfo.getResourceUri().toString(), requestId, fqSliceId, tag)
    }

    // URI layout: /{podId}/slices/{sliceId}/tags/{tag}/changes  (same as POST)

    @GET
    @Produces(RDFMediaTypes.JSON_LD)
    @APIResponseSchema(ChangeReportGraph::class)
    @OpenFgaPolicyEnforcer
    fun listTaggedSliceChangeReports(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        @PathParam("tag") tag: String,
        @QueryParam("pageSize") @Parameter(required = false) @DefaultValue("100") pageSize: Int,
        @QueryParam("cursor") @Parameter(required = false) cursor: Optional<String>
    ): Uni<RestResponse<List<ProcessedChange>>> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(5).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri(3).toASCIIString()
        val sliceStore = repositoryFactory.getVersionedRepository(Slice::class, fqPodId)
        return sliceStore.findById(fqSliceId, tag)
            .onItem().ifNull().failWith(NotFoundException("Slice tag not found: $tag"))
            .onItem().ifNotNull().transformToUni { _ ->
                repositoryFactory.getRepository(ProcessedChange::class, fqPodId).find(
                    filter = "sliceId==\"$fqSliceId\" and sliceTag==\"$tag\"",
                    cursor = cursor.orElse(null),
                    limit = pageSize,
                    sort = Sort.by("id", order = SortOrder.DESC)
                ).map { result ->
                    ResponseBuilder.ok(result.items.map { qualifyProcessedChangeId(uriInfo, it) })
                        .links(*generateLinks(uriInfo, result))
                        .build()
                }
            }
    }

    // URI layout: /{podId}/slices/{sliceId}/tags/{tag}/changes/{changeId}
    //   fqSliceId = getParentUri(4) → removes {changeId}, changes, {tag}, tags
    //   fqPodId   = getParentUri(6) → removes {changeId}, changes, {tag}, tags, {sliceId}, slices

    @Path("{changeId}")
    @GET
    @Produces(RDFMediaTypes.JSON_LD)
    @OpenFgaPolicyEnforcer
    fun getTaggedSliceChangeReport(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        @PathParam("tag") tag: String,
        @PathParam("changeId") changeId: String
    ): Uni<ProcessedChange> {
        val fqSliceId = uriInfo.getResourceUri().getParentUri(4).toASCIIString()
        val fqPodId = uriInfo.getResourceUri().getParentUri(6).toASCIIString()
        return fetchChange(fqPodId, changeId, fqSliceId, tag).map { qualifyProcessedChangeId(uriInfo, it) }
    }

    // URI layout: /{podId}/slices/{sliceId}/tags/{tag}/changes/{changeId}/records
    //   fqPodId = getParentUri(7) → removes records, {changeId}, changes, {tag}, tags, {sliceId}, slices
    //   changeId (@PathParam) is passed directly – the stored change_id is the bare UUID, not a full URI

    @Path("{changeId}/records")
    @GET
    @Produces(RDFMediaTypes.JSON_LD)
    @OpenFgaPolicyEnforcer
    fun getTaggedSliceChangeRecords(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        @PathParam("tag") tag: String,
        @PathParam("changeId") changeId: String,
        @QueryParam("pageSize") @Parameter(required = false) @DefaultValue("2500") pageSize: Int,
        @QueryParam("cursor") @Parameter(required = false) cursor: Optional<String>
    ): Uni<RestResponse<ChangeRecords>> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(7).toASCIIString()
        return listChangeRecords(
            uriInfo,
            ChangeRecordRequest(
                podId = fqPodId,
                changeId = changeId,
                cursor = cursor.orElse(null),
                pageSize = pageSize
            )
        )
    }

}

