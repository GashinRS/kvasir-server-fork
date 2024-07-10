package kvasir.definitions.kg

import graphql.language.Document
import io.smallrye.mutiny.Uni
import kvasir.definitions.annotations.GenerateNoArgConstructor
import java.util.*

interface KnowledgeGraph {

    fun process(request: ChangeRequest): Uni<Void>

    fun query(request: QueryRequest): Uni<QueryResult>

    fun history(request: HistoryRequest): Uni<HistoryResult>

}

data class ChangeRequest(
    val id: String = UUID.randomUUID().toString(),
    val podId: String,
    val graph: String = "", // Graph identifier
    val inserts: List<Map<String, Any>> = emptyList(),
    val deletes: List<Map<String, Any>> = emptyList(),
    val where: List<Map<String, Any>> = emptyList(), // How does this where condition look? (since shift to GraphQL)
)

@GenerateNoArgConstructor
data class QueryRequest(
    val podId: String,
    val graphQL: Document,
    val variables: Map<String, Any>? = null,
    val operationName: String? = null,
)

data class QueryResult(
    val data: Collection<Any>
)

data class HistoryRequest(
    val podId: String
)

data class HistoryResult(
    val results: List<Map<String, Any>>,
    val nextCursor: String? = null
)