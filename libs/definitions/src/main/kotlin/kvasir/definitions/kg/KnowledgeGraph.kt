package kvasir.definitions.kg

import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor
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
    val targetGraphs: Set<String> = emptySet()
)

data class QueryResult(
    val data: List<Map<String, Any>>
) {

    fun toJsonLD(context: Map<String, Any>): Map<String, Any> {
        val graph = transformKeys(data, context)
        // Compact data coming from GraphQL using context
        return JsonLdProcessor.compact(JsonLdProcessor.expand(graph), context, JsonLdOptions())
    }

    private fun transformKeys(graphQLData: Any, context: Map<String, Any>): Any {
        return when (graphQLData) {
            is List<*> -> graphQLData.map { transformKeys(it!!, context) }
            is Map<*, *> -> graphQLData.mapKeys { e ->
                val key = e.key as String
                if (key == "id") {
                    return@mapKeys "@id"
                }
                if (key == "__typename") {
                    return@mapKeys "@type"
                }
                val keyPrefix = key.substringBefore("_")
                if (context.contains(keyPrefix)) {
                    key.replaceFirst(keyPrefix.plus("_"), context[keyPrefix] as String)
                } else {
                    key
                }
            }
                .mapValues { transformKeys(it.value!!, context) }

            else -> graphQLData
        }
    }
}

data class HistoryRequest(
    val podId: String
)

data class HistoryResult(
    val results: List<Map<String, Any>>,
    val nextCursor: String? = null
)