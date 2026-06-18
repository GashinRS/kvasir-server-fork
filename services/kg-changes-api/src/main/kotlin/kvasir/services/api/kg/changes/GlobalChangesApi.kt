package kvasir.services.api.kg.changes

import idlab.quarkus.ext.pep.openfga.model.annotations.OpenFgaPolicyEnforcer
import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Response
import kvasir.definitions.auth.AuthConstants
import kvasir.definitions.kg.ChangeRecordRequest
import kvasir.definitions.kg.ChangeRecords
import kvasir.definitions.kg.Pod
import kvasir.definitions.kg.changes.ProcessedChange
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
@Path("{podId}/changes")
class InboxApi : AbstractChangesApi() {

    @POST
    @Consumes(RDFMediaTypes.JSON_LD)
    @Produces(RDFMediaTypes.JSON_LD)
    @Operation(
        summary = "Perform mutations on the KG.",
        description = "Post a change request, containing the requested mutations, to the inbox of the specified pod. " +
                "When `sync=true` the request blocks until the change has been processed and returns the resulting " +
                "change report (HTTP 200). The default async behaviour (HTTP 201 + Location header) is preserved " +
                "when `sync` is omitted or set to `false`.",
    )
    @APIResponse(responseCode = "201", description = "Change request accepted (async mode).")
    @APIResponse(responseCode = "200", description = "Change request processed (sync mode).")
    @APIResponse(responseCode = "408", description = "Timed out waiting for the change result (sync mode).")
    @OpenFgaPolicyEnforcer
    fun processChangeRequest(
        @PathParam("podId") podId: String,
        @QueryParam("sync") @Parameter(
            description = "When `true`, block until the change has been fully processed and return the resulting " +
                    "ProcessedChange report (HTTP 200). Defaults to `false` (async, HTTP 201 + Location).",
            required = false
        ) @DefaultValue("false") sync: Boolean,
        input: ChangeRequestInput
    ): Uni<Response> {
        val fqPodId = uriInfo.getResourceUri().getParentUri().toString()
        return repositoryFactory.getRepository(Pod::class).findById(fqPodId)
            .onItem().ifNull().failWith(NotFoundException("Pod not found"))
            .onItem().ifNotNull().transformToUni { _ ->
                val changeCommand = input.toChangeRequest(
                    fqPodId,
                    securityIdentity.takeIf { it.isResolvable }?.get()?.principal?.name
                        ?: AuthConstants.ANONYMOUS_USERNAME
                )
                handlePublishChange(fqPodId, changeCommand, sync)
            }
    }

    @Path("/pending/{requestId}")
    @GET
    @Produces(RDFMediaTypes.JSON_LD)
    fun getPendingChangeRequest(@PathParam("requestId") requestId: String): Uni<Response> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(3).toString()
        return handlePendingChangeRequest(fqPodId, uriInfo.getResourceUri().toString(), requestId)
    }

    @GET
    @Produces(RDFMediaTypes.JSON_LD)
    @APIResponseSchema(ChangeReportGraph::class)
    @OpenFgaPolicyEnforcer
    fun listChanges(
        @PathParam("podId") podId: String,
        @QueryParam("pageSize") @Parameter(required = false) @DefaultValue("100") pageSize: Int,
        @QueryParam("cursor") @Parameter(required = false) cursor: Optional<String>
    ): Uni<RestResponse<List<ProcessedChange>>> {
        val fqPodId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return repositoryFactory.getRepository(ProcessedChange::class, fqPodId).find(
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
    fun getChange(
        @PathParam("podId") podId: String,
        @PathParam("changeId") changeId: String
    ): Uni<ProcessedChange> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(2).toASCIIString()
        return fetchChange(fqPodId, changeId).map { qualifyProcessedChangeId(uriInfo, it) }
    }

    @Path("{changeId}/records")
    @GET
    @Produces(RDFMediaTypes.JSON_LD)
    @OpenFgaPolicyEnforcer
    fun getChangeRecords(
        @PathParam("podId") podId: String,
        @PathParam("changeId") changeId: String,
        @QueryParam("pageSize") @Parameter(required = false) @DefaultValue("2500") pageSize: Int,
        @QueryParam("cursor") @Parameter(required = false) cursor: Optional<String>
    ): Uni<RestResponse<ChangeRecords>> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(3).toASCIIString()
        return listChangeRecords(
            uriInfo,
            ChangeRecordRequest(
                podId = fqPodId,
                changeId = changeId,
                cursor = cursor.orElse(null),
                pageSize = pageSize
            )
        ).onFailure().invoke { err -> Log.warn("Error while fetching change records", err) }
    }

}