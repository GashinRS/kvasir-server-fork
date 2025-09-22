package kvasir.services.api.kg.query

import com.fasterxml.jackson.annotation.JsonProperty
import idlab.quarkus.ext.pep.openfga.model.annotations.OpenFgaPolicyEnforcer
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Link
import jakarta.ws.rs.core.Response
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.kg.*
import kvasir.definitions.kg.changes.ChangeHistoryFactory
import kvasir.definitions.kg.changes.ChangeReport
import kvasir.definitions.kg.changes.ChangeReportStatusEntry
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.persistence.Sort
import kvasir.definitions.persistence.SortOrder
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.utils.http.KvasirUriInfo
import kvasir.utils.http.getParentUri
import kvasir.utils.idgen.ChangeRequestId
import kvasir.utils.idgen.InvalidChangeRequestIdException
import kvasir.utils.rdf.RDFTransformer
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter
import org.eclipse.microprofile.openapi.annotations.responses.APIResponseSchema
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.jboss.resteasy.reactive.RestResponse
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder
import java.util.*

@Path("")
@Tag(name = ApiDocTags.KG_CHANGES_API)
class ChangeHistoryApi(
    val changeHistoryFactory: ChangeHistoryFactory,
    val knowledgeGraph: KnowledgeGraph,
    val uriInfo: KvasirUriInfo
) {

    @Path("{podId}/changes")
    @GET
    @Produces(RDFMediaTypes.JSON_LD)
    @APIResponseSchema(ChangeReportGraph::class)
    @OpenFgaPolicyEnforcer
    fun listChangeReports(
        @PathParam("podId") podId: String,
        @QueryParam("pageSize") @Parameter(required = false) @DefaultValue("100") pageSize: Int,
        @QueryParam("cursor") @Parameter(required = false) cursor: Optional<String>
    ): Uni<RestResponse<List<ChangeReport>>> {
        val fqPodId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return changeHistoryFactory.getChangeHistory(fqPodId).find(
            cursor = cursor.orElse(null),
            limit = pageSize,
            sort = Sort.by("writeTs", order = SortOrder.DESC)
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
    @APIResponseSchema(ChangeReportGraph::class)
    @OpenFgaPolicyEnforcer
    fun listSliceChangeReports(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        @QueryParam("pageSize") @Parameter(required = false) @DefaultValue("100") pageSize: Int,
        @QueryParam("cursor") @Parameter(required = false) cursor: Optional<String>
    ): Uni<RestResponse<List<ChangeReport>>> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(3).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return changeHistoryFactory.getChangeHistory(fqPodId).find(
            filter = "sliceId==\"$fqSliceId\"",
            cursor = cursor.orElse(null),
            limit = pageSize,
            sort = Sort.by("writeTs", order = SortOrder.DESC)
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
    @OpenFgaPolicyEnforcer
    fun getChangeReport(
        @PathParam("podId") podId: String,
        @PathParam("changeId") changeId: String
    ): Uni<ChangeReport> {
        val id = uriInfo.getResourceUri().toASCIIString()
        val fqPodId = uriInfo.getResourceUri().getParentUri(2).toASCIIString()
        return changeHistoryFactory.getChangeHistory(fqPodId).findById(id)
            .onItem().ifNotNull().transform { it!! }
            .onItem().ifNull().switchTo {
                try {
                    val changeRequestId = ChangeRequestId.fromId(id)
                    Uni.createFrom().item(
                        ChangeReport(
                            id,
                            "", // TODO: can we get the requesting user here, without encoding it in the ID?
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
    @OpenFgaPolicyEnforcer
    fun getSliceChangeReport(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        @PathParam("changeId") changeId: String
    ): Uni<ChangeReport> {
        val fqChangeId = uriInfo.getResourceUri().toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri(2).toASCIIString()
        val fqPodId = uriInfo.getResourceUri().getParentUri(4).toASCIIString()
        return changeHistoryFactory.getChangeHistory(fqPodId).findById(fqChangeId)
            .onItem().ifNotNull().transform { it!! }
            .onItem().ifNull().switchTo {
                try {
                    val changeRequestId = ChangeRequestId.fromId(fqChangeId)
                    Uni.createFrom().item(
                        ChangeReport(
                            fqChangeId,
                            "", // TODO: can we get the requesting user here, without encoding it in the ID?
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
    @OpenFgaPolicyEnforcer
    fun getChangeRecords(
        @PathParam("podId") podId: String,
        @PathParam("changeId") changeId: String,
        @QueryParam("pageSize") @Parameter(required = false) @DefaultValue("2500") pageSize: Int,
        @QueryParam("cursor") @Parameter(required = false) cursor: Optional<String>
    ): Uni<RestResponse<ChangeRecords>> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(3).toASCIIString()
        val fqChangeRequestId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return listChangeRecords(
            ChangeRecordRequest(
                podId = fqPodId,
                changeRequestId = fqChangeRequestId,
                cursor = cursor.orElse(null),
                pageSize = pageSize
            )
        )
    }

    @Path("{podId}/slices/{sliceId}/changes/{changeId}/records")
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
        val fqChangeId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return listChangeRecords(
            ChangeRecordRequest(
                podId = fqPodId,
                changeRequestId = fqChangeId,
                cursor = cursor.orElse(null),
                pageSize = pageSize
            )
        )
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

    private fun listChangeRecords(request: ChangeRecordRequest): Uni<RestResponse<ChangeRecords>> {
        return knowledgeGraph.getChangeRecords(request).map { results ->
            try {
                val response = if (results.items.isNotEmpty()) {
                    ChangeRecords(
                        mapOf("kss" to KvasirVocab.baseUri),
                        request.changeRequestId,
                        results.items.first().timestamp,
                        results.items.filter { it.type == ChangeRecordType.DELETE }
                            .map { it.statement }.takeIf { it.isNotEmpty() }
                            ?.let { RDFTransformer.statementsToJsonLD(it) },
                        results.items.filter { it.type == ChangeRecordType.INSERT }
                            .map { it.statement }.takeIf { it.isNotEmpty() }
                            ?.let { RDFTransformer.statementsToJsonLD(it) }
                    )
                } else {
                    val parsedId = ChangeRequestId.fromId(request.changeRequestId)
                    ChangeRecords(
                        mapOf("kss" to KvasirVocab.baseUri),
                        request.changeRequestId,
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


}

// This type is only needed to generate OpenAPI documentation.
@GenerateNoArgConstructor
data class ChangeReportGraph(
    @get:JsonProperty(JsonLdKeywords.graph)
    val graph: List<ChangeReport>
)
