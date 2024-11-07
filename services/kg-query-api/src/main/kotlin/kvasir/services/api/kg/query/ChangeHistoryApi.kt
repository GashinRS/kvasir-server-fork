package kvasir.services.api.kg.query

import io.smallrye.mutiny.Uni
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.UriInfo
import kvasir.definitions.kg.ChangeHistoryRequest
import kvasir.definitions.kg.ChangeRecord
import kvasir.definitions.kg.ChangeReport
import kvasir.definitions.kg.KnowledgeGraph

@Path("/{podId}/changes/history")
class ChangeHistoryApi(
    val knowledgeGraph: KnowledgeGraph,
    val uriInfo: UriInfo
) {

    @GET
    fun listChangeReports(@PathParam("podId") podId: String): Uni<List<ChangeReport>> {
        val podId = uriInfo.absolutePath.toString()
        return knowledgeGraph.listChanges(ChangeHistoryRequest(podId))
    }

    @Path("{changeId}")
    @GET
    fun getChangeReport(@PathParam("podId") podId: String, @PathParam("changeId") changeId: String): Uni<ChangeReport> {
        val podId = uriInfo.absolutePath.toString().substringBefore("/changes/history")
        return knowledgeGraph.getChange(
            ChangeHistoryRequest(
                podId = podId,
                changeRequestId = uriInfo.absolutePath.toString()
            )
        )
            .onItem().ifNotNull().transform { it!! }
            .onItem().ifNull().failWith(NotFoundException("No change report found!"))
    }

    @Path("{changeId}/records")
    @GET
    fun getChangeRecords(
        @PathParam("podId") podId: String,
        @PathParam("changeId") changeId: String
    ): Uni<List<ChangeRecord>> {
        val podId = uriInfo.absolutePath.toString().substringBefore("/changes/history")
        return knowledgeGraph.getChangeRecords(
            ChangeHistoryRequest(
                podId = podId,
                changeRequestId = uriInfo.absolutePath.toString()
            )
        )
    }

}