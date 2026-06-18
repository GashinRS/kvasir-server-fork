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
@Path("{podId}/slices/{sliceId}/changes")
class SliceChangesApi : AbstractChangesApi() {

    @POST
    @Consumes(RDFMediaTypes.JSON_LD)
    @Operation(
        summary = "Perform mutations on a specific slice of the KG.",
        description = "Post a change request, containing the requested mutations, to a slice inbox of the specified pod.",
    )
    @APIResponse(responseCode = "201", description = "Change request created.")
    @OpenFgaPolicyEnforcer
    fun processSliceChangeRequest(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        @QueryParam("sync") @Parameter(
            description = "When `true`, block until the change has been fully processed and return the resulting " +
                    "ProcessedChange report (HTTP 200). Defaults to `false` (async, HTTP 201 + Location).",
            required = false
        ) @DefaultValue("false") sync: Boolean,
        input: ChangeRequestInput
    ): Uni<Response> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(3).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return repositoryFactory.getVersionedRepository(Slice::class, fqPodId).findDefaultForId(fqSliceId)
            .onItem().ifNull().failWith(NotFoundException("Slice not found"))
            .onItem().ifNotNull().transformToUni { slice ->
                if (slice!!.supportsChanges) {
                    val changeCommand = input.toChangeRequest(
                        fqPodId,
                        securityIdentity.takeIf { it.isResolvable }?.get()?.principal?.name
                            ?: AuthConstants.ANONYMOUS_USERNAME,
                        fqSliceId
                    )
                    handlePublishChange(fqPodId, changeCommand, sync)
                } else {
                    Uni.createFrom().item(Response.status(Response.Status.METHOD_NOT_ALLOWED).build())
                }
            }
    }

    @Path("/pending/{requestId}")
    @GET
    @Produces(RDFMediaTypes.JSON_LD)
    fun getPendingChangeRequest(@PathParam("requestId") requestId: String): Uni<Response> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(5).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri(3).toASCIIString()
        return handlePendingChangeRequest(fqPodId, uriInfo.getResourceUri().toString(), requestId, fqSliceId)
    }

    @GET
    @Produces(RDFMediaTypes.JSON_LD)
    @APIResponseSchema(ChangeReportGraph::class)
    @OpenFgaPolicyEnforcer
    fun listSliceChangeReports(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        @QueryParam("pageSize") @Parameter(required = false) @DefaultValue("100") pageSize: Int,
        @QueryParam("cursor") @Parameter(required = false) cursor: Optional<String>
    ): Uni<RestResponse<List<ProcessedChange>>> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(3).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return repositoryFactory.getRepository(ProcessedChange::class, fqPodId).find(
            filter = "sliceId==\"$fqSliceId\"",
            cursor = cursor.orElse(null),
            limit = pageSize,
            sort = Sort.by("id", order = SortOrder.DESC)
        )
            .map { result ->
                ResponseBuilder.ok(result.items.map { qualifyProcessedChangeId(uriInfo, it) })
                    .links(*generateLinks(uriInfo, result))
                    .build()
            }
    }

    @Path("{changeId}")
    @GET
    @Produces(RDFMediaTypes.JSON_LD)
    @OpenFgaPolicyEnforcer
    fun getSliceChangeReport(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        @PathParam("changeId") changeId: String
    ): Uni<ProcessedChange> {
        val fqSliceId = uriInfo.getResourceUri().getParentUri(2).toASCIIString()
        val fqPodId = uriInfo.getResourceUri().getParentUri(4).toASCIIString()
        return fetchChange(fqPodId, changeId, fqSliceId).map { qualifyProcessedChangeId(uriInfo, it) }
    }

    @Path("{changeId}/records")
    @GET
    @Produces(RDFMediaTypes.JSON_LD)
    @OpenFgaPolicyEnforcer
    fun getSliceChangeRecords(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        @PathParam("changeId") changeId: String,
        @QueryParam("pageSize") @Parameter(required = false) @DefaultValue("2500") pageSize: Int,
        @QueryParam("cursor") @Parameter(required = false) cursor: Optional<String>
    ): Uni<RestResponse<ChangeRecords>> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(5).toASCIIString()
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