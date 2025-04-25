package kvasir.services.api.kg.query

import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Link
import jakarta.ws.rs.core.Response
import kvasir.definitions.kg.*
import kvasir.definitions.kg.changes.ChangeHistory
import kvasir.definitions.kg.changes.ChangeHistoryRequest
import kvasir.definitions.kg.changes.ChangeReport
import kvasir.definitions.kg.changes.ChangeReportStatusEntry
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.utils.http.KvasirUriInfo
import kvasir.utils.http.getParentUri
import kvasir.utils.idgen.ChangeRequestId
import kvasir.utils.idgen.InvalidChangeRequestIdException
import kvasir.utils.rdf.RDFTransformer
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.jboss.resteasy.reactive.RestResponse
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder
import java.util.*

@Path("")
@Tag(name = ApiDocTags.KG_CHANGES_API)
class ChangeHistoryApi(
    val changeHistory: ChangeHistory,
    val knowledgeGraph: KnowledgeGraph,
    val uriInfo: KvasirUriInfo,
    private val securityIdentity: SecurityIdentity
) {

    @Path("{podId}/changes")
    @GET
    @Produces(RDFMediaTypes.JSON_LD)
    fun listChangeReports(
        @PathParam("podId") podId: String,
        @QueryParam("pageSize") @Parameter(required = false) @DefaultValue("100") pageSize: Int,
        @QueryParam("cursor") @Parameter(required = false) cursor: Optional<String>
    ): Uni<RestResponse<List<ChangeReport>>> {
        val fqPodId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return changeHistory.list(
            ChangeHistoryRequest(
                podId = fqPodId,
                cursor = cursor.orElse(null),
                pageSize = pageSize
            )
        )
            .map { result ->
                ResponseBuilder.ok(result.items)
                    .links(*generateLinks(result))
                    .build()
            }
    }

    @Path("{podId}/slices/{sliceId}/changes")
    @GET
    @Produces(RDFMediaTypes.JSON_LD)
    fun listSliceChangeReports(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        @QueryParam("pageSize") @Parameter(required = false) @DefaultValue("100") pageSize: Int,
        @QueryParam("cursor") @Parameter(required = false) cursor: Optional<String>
    ): Uni<RestResponse<List<ChangeReport>>> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(3).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return changeHistory.list(
            ChangeHistoryRequest(
                podId = fqPodId,
                sliceId = fqSliceId,
                cursor = cursor.orElse(null),
                pageSize = pageSize
            )
        )
            .map { result ->
                ResponseBuilder.ok(result.items)
                    .links(*generateLinks(result))
                    .build()
            }
    }


    @Path("{podId}/changes/{changeId}")
    @GET
    @Produces(RDFMediaTypes.JSON_LD)
    fun getChangeReport(
        @PathParam("podId") podId: String,
        @PathParam("changeId") changeId: String
    ): Uni<ChangeReport> {
        val id = uriInfo.getResourceUri().toASCIIString()
        val fqPodId = uriInfo.getResourceUri().getParentUri(2).toASCIIString()
        return changeHistory.get(
            ChangeHistoryRequest(
                podId = fqPodId,
                changeRequestId = id
            )
        )
            .onItem().ifNotNull().transform { it!! }
            .onItem().ifNull().switchTo {
                try {
                    val changeRequestId = ChangeRequestId.fromId(id)
                    Uni.createFrom().item(
                        ChangeReport(
                            id,
                            fqPodId,
                            listOf(ChangeReportStatusEntry(changeRequestId.timestamp(), ChangeStatusCode.QUEUED))
                        )
                    )
                } catch (err: IllegalArgumentException) {
                    Uni.createFrom().failure(NotFoundException("No change report found!"))
                }
            }
    }

    @Path("{podId}/slices/{sliceId}/changes/{changeId}")
    @GET
    @Produces(RDFMediaTypes.JSON_LD)
    fun getSliceChangeReport(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        @PathParam("changeId") changeId: String
    ): Uni<ChangeReport> {
        val fqChangeId = uriInfo.getResourceUri().toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri(2).toASCIIString()
        val fqPodId = uriInfo.getResourceUri().getParentUri(4).toASCIIString()
        return changeHistory.get(
            ChangeHistoryRequest(
                podId = fqPodId,
                sliceId = fqSliceId,
                changeRequestId = fqChangeId
            )
        )
            .onItem().ifNotNull().transform { it!! }
            .onItem().ifNull().switchTo {
                try {
                    val changeRequestId = ChangeRequestId.fromId(fqChangeId)
                    Uni.createFrom().item(
                        ChangeReport(
                            fqChangeId,
                            fqPodId,
                            listOf(ChangeReportStatusEntry(changeRequestId.timestamp(), ChangeStatusCode.QUEUED)),
                            fqSliceId
                        )
                    )
                } catch (err: IllegalArgumentException) {
                    Uni.createFrom().failure(NotFoundException("No change report found!"))
                }
            }
    }

    @Path("{podId}/changes/{changeId}/records")
    @GET
    @Produces(RDFMediaTypes.JSON_LD)
    fun getChangeRecords(
        @PathParam("podId") podId: String,
        @PathParam("changeId") changeId: String,
        @QueryParam("pageSize") @Parameter(required = false) @DefaultValue("2500") pageSize: Int,
        @QueryParam("cursor") @Parameter(required = false) cursor: Optional<String>
    ): Uni<RestResponse<ChangeRecords>> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(3).toASCIIString()
        val fqChangeRequestId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return knowledgeGraph.getChangeRecords(
            ChangeRecordRequest(
                podId = fqPodId,
                changeRequestId = fqChangeRequestId,
                cursor = cursor.orElse(null),
                pageSize = pageSize
            )
        ).map { results ->
            try {
                val response = if (results.items.isNotEmpty()) {
                    ChangeRecords(
                        mapOf("kss" to KvasirVocab.baseUri),
                        fqChangeRequestId,
                        results.items.first().timestamp,
                        results.items.filter { it.type == ChangeRecordType.DELETE }
                            .map { it.statement }.takeIf { it.isNotEmpty() }
                            ?.let { RDFTransformer.statementsToJsonLD(it) },
                        results.items.filter { it.type == ChangeRecordType.INSERT }
                            .map { it.statement }.takeIf { it.isNotEmpty() }
                            ?.let { RDFTransformer.statementsToJsonLD(it) }
                    )
                } else {
                    val parsedId = ChangeRequestId.fromId(fqChangeRequestId)
                    ChangeRecords(
                        mapOf("kss" to KvasirVocab.baseUri),
                        fqChangeRequestId,
                        parsedId.timestamp()
                    )
                }
                ResponseBuilder.ok(response)
                    .links(*generateLinks(results))
                    .build()
            } catch (err: InvalidChangeRequestIdException) {
                RestResponse.status(Response.Status.BAD_REQUEST)
            }
        }
    }

    @Path("{podId}/slices/{sliceId}/changes/{changeId}/records")
    @GET
    @Produces(RDFMediaTypes.JSON_LD)
    fun getSliceChangeRecords(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        @PathParam("changeId") changeId: String,
        @QueryParam("pageSize") @Parameter(required = false) @DefaultValue("2500") pageSize: Int,
        @QueryParam("cursor") @Parameter(required = false) cursor: Optional<String>
    ): Uni<RestResponse<ChangeRecords>> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(5).toASCIIString()
        val fqChangeId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return knowledgeGraph.getChangeRecords(
            ChangeRecordRequest(
                podId = fqPodId,
                changeRequestId = fqChangeId,
                cursor = cursor.orElse(null),
                pageSize = pageSize
            )
        ).map { results ->
            val response = results.items.groupBy { result -> result.changeRequestId }
                .map { (changeRequestId, records) ->
                    ChangeRecords(
                        mapOf("kss" to KvasirVocab.baseUri),
                        changeRequestId,
                        records.first().timestamp,
                        records.filter { it.type == ChangeRecordType.DELETE }
                            .map { it.statement }.takeIf { it.isNotEmpty() }
                            ?.let { RDFTransformer.statementsToJsonLD(it) },
                        records.filter { it.type == ChangeRecordType.INSERT }
                            .map { it.statement }.takeIf { it.isNotEmpty() }
                            ?.let { RDFTransformer.statementsToJsonLD(it) }
                    )
                }.first()
            ResponseBuilder.ok(response)
                .links(*generateLinks(results))
                .build()
        }
    }

    private fun generateLinks(result: PagedResult<*>): Array<Link> {
        return listOfNotNull(
            result.nextCursor?.let {
                Link.fromUri(uriInfo.getAbsoluteUri("cursor" to it)).rel("next").build()
            },
            result.previousCursor?.let {
                Link.fromUri(uriInfo.getAbsoluteUri("cursor" to it)).rel("previous").build()
            }
        ).toTypedArray()
    }


}