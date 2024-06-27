package kvasir.services.api.kg.query

import io.smallrye.mutiny.Uni
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.kg.QueryResult

@Path("{podId}/kg/query")
class QueryApi(
    private val knowledgeGraph: KnowledgeGraph
) {

    @POST
    @Produces("application/ld+json")
    fun query(@PathParam("podId") podId: String, input: QueryInput): Uni<ContextualizedQueryResult> {
        val req = input.toQueryRequest(podId)
        return knowledgeGraph.query(req).map { ContextualizedQueryResult(input.providedContext, it) }
    }

    @POST
    @Path("raw")
    fun rawQuery(@PathParam("podId") podId: String, input: String): Uni<QueryResult> {
        return knowledgeGraph.rawQuery(input)
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

data class ContextualizedQueryResult(
    val context: Map<String, Any>,
    val result: QueryResult
)