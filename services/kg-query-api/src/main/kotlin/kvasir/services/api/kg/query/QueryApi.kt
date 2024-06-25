package kvasir.services.api.kg.query

import io.smallrye.mutiny.Uni
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.kg.QueryResult

@Path("{podId}/kg/query")
class QueryApi(
    private val knowledgeGraph: KnowledgeGraph
) {

    @POST
    fun query(@PathParam("podId") podId: String, input: QueryInput): Uni<QueryResult> {
        val req = input.toQueryRequest(podId)
        return knowledgeGraph.query(req)
    }

}

data class QueryInput(
    val providedContext: Map<String, Any>,
    val from: List<String> = emptyList(),
    val where: List<Map<String, Any>> = emptyList(),
    val select: List<Map<String, Any>> = emptyList()
) {
    fun toQueryRequest(podId: String): QueryRequest {
        return QueryRequest(
            podId = podId,
            from = from,
            where = where,
            select = select
        )
    }
}