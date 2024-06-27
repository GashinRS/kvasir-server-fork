package kvasir.definitions.kg

import io.smallrye.mutiny.Uni
import java.util.*

interface KnowledgeGraph {

    fun process(request: ChangeRequest): Uni<Void>

    fun query(request: QueryRequest): Uni<QueryResult>

    fun rawQuery(q: String): Uni<QueryResult>

    fun history(request: HistoryRequest): Uni<HistoryResult>

}

data class ChangeRequest(
    val id: String = UUID.randomUUID().toString(),
    val podId: String,
    val graph: String = "", // Graph identifier
    val inserts: List<Map<String, Any>> = emptyList(),
    val deletes: List<Map<String, Any>> = emptyList(),
    val where: List<Map<String, Any>> = emptyList(),
)

data class QueryRequest(
    val podId: String,
    val from: List<String> = emptyList(), // Graph identifiers
    val where: List<Map<String, Any>>,
    val select: List<Map<String, Any>>
)

data class QueryResult(
    val results: List<Map<String, Any>>,
    val nextCursor: String? = null
)

data class HistoryRequest(
    val podId: String
)

data class HistoryResult(
    val results: List<Map<String, Any>>,
    val nextCursor: String? = null
)