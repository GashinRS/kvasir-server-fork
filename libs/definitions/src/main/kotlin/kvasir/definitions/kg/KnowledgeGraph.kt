package kvasir.definitions.kg

import com.fasterxml.jackson.annotation.JsonInclude
import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor
import graphql.language.Document
import io.smallrye.mutiny.Uni
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.kg.changeops.Assertion
import java.util.*

interface KnowledgeGraph {

    fun process(request: ChangeRequest): Uni<Void>

    fun query(request: QueryRequest): Uni<QueryResult>

    fun history(request: HistoryRequest): Uni<HistoryResult>

}

data class ChangeRequest(
    val id: String = UUID.randomUUID().toString(),
    val context: Map<String, Any> = emptyMap(),
    val podId: String,
    val graph: String = "", // Graph identifier
    // The Change Request will only be applied if all assertions resolve to true.
    val assert: List<Assertion> = emptyList(),
    val where: String? = null,
    val insert: List<Any> = emptyList(),
    val delete: List<Any> = emptyList()
)

@GenerateNoArgConstructor
data class QueryRequest(
    val podId: String,
    val graphQL: Document,
    val variables: Map<String, Any>? = null,
    val operationName: String? = null,
    val targetGraphs: Set<String> = emptySet()
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class QueryResult(
    val data: List<Map<String, Any>>,
    val errors: List<Map<String, Any>>? = null
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