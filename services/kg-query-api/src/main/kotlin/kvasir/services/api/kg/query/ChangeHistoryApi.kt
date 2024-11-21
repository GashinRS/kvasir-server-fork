package kvasir.services.api.kg.query

import io.smallrye.mutiny.Uni
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Link
import jakarta.ws.rs.core.UriInfo
import kvasir.definitions.kg.*
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.utils.rdf.RDFTransformer
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.jboss.resteasy.reactive.RestResponse
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder
import java.util.*

@Path("")
@Tag(name = ApiDocTags.KG_CHANGES_API)
class ChangeHistoryApi(
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
        return knowledgeGraph.listChanges(
            ChangeHistoryRequest(
                podId = podId,
                cursor = cursor.orElse(null),
                pageSize = pageSize
            )
        )
            .map { result ->
                ResponseBuilder.ok(result.items)
                    .links(
                        *listOfNotNull(
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
                    )
                    .build()
            }
    }

    @Path("{podId}/changes/{changeId}")
    @GET
    @Produces(RDFMediaTypes.JSON_LD)
    fun getChangeReport(@PathParam("podId") podId: String, @PathParam("changeId") changeId: String): Uni<ChangeReport> {
        val podId = uriInfo.absolutePath.toString().substringBefore("/changes")
        return knowledgeGraph.getChange(
            ChangeHistoryRequest(
                podId = podId,
                changeRequestId = uriInfo.absolutePath.toString()
            )
        )
            .onItem().ifNotNull().transform { it!! }
            .onItem().ifNull().failWith(NotFoundException("No change report found!"))
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
        val podId = uriInfo.absolutePath.toString().substringBefore("/changes")
        return knowledgeGraph.getChangeRecords(
            ChangeHistoryRequest(
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
                .links(
                    *listOfNotNull(
                        results.nextCursor?.let {
                            Link.fromUri(
                                uriInfo.absolutePathBuilder.replaceQueryParam(
                                    "cursor",
                                    it
                                ).build()
                            ).rel("next").build()
                        },
                        results.previousCursor?.let {
                            Link.fromUri(
                                uriInfo.absolutePathBuilder.replaceQueryParam(
                                    "cursor",
                                    it
                                ).build()
                            ).rel("previous").build()
                        }
                    ).toTypedArray()
                )
                .build()
        }
    }

}