package kvasir.services.api.kg.query

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam

@Path("/{podId}/changes/history")
class ChangeHistoryApi {

    @GET
    fun listChanges(@PathParam("podId") podId: String) {
        TODO()
    }

    @Path("{changeId}")
    @GET
    fun getChange(@PathParam("podId") podId: String, @PathParam("changeId") changeId: String) {
        TODO()
    }

}