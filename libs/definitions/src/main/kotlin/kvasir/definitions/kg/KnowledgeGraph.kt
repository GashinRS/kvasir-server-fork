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

interface SliceStore {

    fun persist(segment: Slice): Uni<Void>

    fun list(podId: String): Uni<List<SliceSummary>>

    fun getById(segmentId: String): Uni<Slice>

    fun deleteById(segmentId: String): Uni<Void>
}

data class ChangeRequest(
    val id: String = UUID.randomUUID().toString(),
    val context: Map<String, Any> = emptyMap(),
    val podId: String,
    val graph: String = "", // Graph identifier
    // The Change Request will only be applied if all assertions resolve to true.
    val assert: List<Assertion> = emptyList(),
    /**
     * The with-clause value is a GraphQL query expression.
     * The results of this query can be referenced in the insert and delete operations using JSONata template strings.
     */
    val with: String? = null,
    /**
     * Insert instructions as a List of:
     * - A Map<String, Any> with a property "@type" set to "kss:S3Reference" representing a reference to an S3 object
     * - Any other Map<String, Any> instance, representing actual JSON-LD data to be inserted
     * - A String representing a JSONata template to be resolved
     */
    val insert: List<Any> = emptyList(),
    /**
     * Insert instructions as a List of:
     * - A Map<String, Any> with a property "@type" set to "kss:S3Reference" representing a reference to an S3 object
     * - Any other Map<String, Any> instance, representing actual JSON-LD data to be inserted
     * - A String representing a JSONata template to be resolved
     */
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
    val data: Map<String, Any>,
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

data class Slice(
    val id: String,
    val context: Map<String, Any>,
    val podId: String,
    val name: String,
    val description: String,
    val spec: String,
    val targetGraphs: Set<String> = emptySet()
)

data class SliceSummary(
    val id: String,
    val name: String,
    val description: String
)