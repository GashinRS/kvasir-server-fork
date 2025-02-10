package kvasir.services.api.kg.query

import io.smallrye.mutiny.Uni
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Link
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import kvasir.definitions.kg.*
import kvasir.definitions.kg.changes.ChangeHistoryRequest
import kvasir.definitions.kg.changes.ChangeReport
import kvasir.definitions.kg.changes.ChangeHistory
import kvasir.definitions.kg.changes.ChangeReportStatusEntry
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.rdf.RDFMediaTypes
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
    val uriInfo: UriInfo
) {

    @Path("{podId}/changes")
    @GET
    @Produces(RDFMediaTypes.JSON_LD)
    fun listChangeReports(
        @PathParam("podId") podId: String,
        @QueryParam("pageSize") @Parameter(required = false) @DefaultValue("100") pageSize: Int,
        @QueryParam("cursor") @Parameter(required = false) cursor: Optional<String>
    ): Uni<RestResponse<List<ChangeReport>>> {
        val podId = uriInfo.absolutePath.toString().substringBefore("/changes")
        return changeHistory.list(
            ChangeHistoryRequest(
                podId = podId,
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
        val podId = uriInfo.absolutePath.toString().substringBefore("/slices/$sliceId/changes")
        return changeHistory.list(
            ChangeHistoryRequest(
                podId = podId,
                sliceId = sliceId,
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
        val id = uriInfo.absolutePath.toString()
        val podId = uriInfo.absolutePath.toString().substringBefore("/changes")
        return changeHistory.get(
            ChangeHistoryRequest(
                podId = podId,
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
                            podId,
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
        val fqChangeId = uriInfo.absolutePath.toString()
        val fqSliceId = uriInfo.absolutePath.toString().substringBeforeLast("/changes")
        val fqPodId = uriInfo.absolutePath.toString().substringBefore("/slices/$sliceId/changes")
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
        val fqPodId = uriInfo.absolutePath.toString().substringBefore("/changes")
        val fqChangeRequestId = uriInfo.absolutePath.toString().substringBefore("/records")
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
        val podId = uriInfo.absolutePath.toString().substringBefore("/slices/$sliceId/changes")
        return knowledgeGraph.getChangeRecords(
            ChangeRecordRequest(
                podId = podId,
                changeRequestId = uriInfo.absolutePath.toString().substringBefore("/records"),
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
                Link.fromUri(
                    uriInfo.absolutePathBuilder.replaceQueryParam(
                        "cursor",
                        it
                    ).build()
                ).rel("next").build()
            },
            result.previousCursor?.let {
                Link.fromUri(
                    uriInfo.absolutePathBuilder.replaceQueryParam(
                        "cursor",
                        it
                    ).build()
                ).rel("previous").build()
            }
        ).toTypedArray()
    }


}