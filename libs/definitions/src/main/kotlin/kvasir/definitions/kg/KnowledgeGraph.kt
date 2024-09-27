package kvasir.definitions.kg

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.kg.changeops.Assertion
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import java.util.*

interface KnowledgeGraph {

    fun process(request: ChangeRequest): Uni<Void>

    fun query(request: QueryRequest): Uni<QueryResult>

    fun history(request: HistoryRequest): Uni<HistoryResult>

}

interface SliceStore {

    fun persist(segment: Slice): Uni<Void>

    fun list(podId: String): Uni<List<SliceSummary>>

    fun getById(podId: String, segmentId: String): Uni<Slice>

    fun deleteById(podId: String, segmentId: String): Uni<Void>
}

interface ReferenceLoader {

    fun isSupported(reference: Map<String, Any>): Boolean

    fun loadReference(podId: String, targetGraph: String, reference: Map<String, Any>): Multi<RDFStatement>
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
     * - Any Map<String, Any> instance, representing actual JSON-LD data to be inserted
     * - A String representing a JSONata template to be resolved
     */
    val insert: List<Any> = emptyList(),
    /**
     * Insert instructions as a List of:
     * - Any Map<String, Any> instance, representing actual JSON-LD data to be inserted
     * - A String representing a JSONata template to be resolved
     */
    val delete: List<Any> = emptyList(),
    /**
     * Insert instruction to ingest data from an external source. At the moment, only the internal Pod S3 is supported.
     * An S3 reference is modeled as a JSON-LD object with a property "@type" set to "kss:S3Reference".
     *
     * Cannot be combined with insert or delete.
     */
    val insertFromRefs: List<Map<String, Any>> = emptyList(),
    /**
     * Delete instruction to ingest data from an external source. At the moment, only the internal Pod S3 is supported.
     * An S3 reference is modeled as a JSON-LD object with a property "@type" set to "kss:S3Reference".
     *
     * Cannot be combined with insert or delete.
     */
    val deleteFromRefs: List<Map<String, Any>> = emptyList()
) {

    init {
        require(insert.isNotEmpty() || delete.isNotEmpty() || insertFromRefs.isNotEmpty() || deleteFromRefs.isNotEmpty()) {
            "At least one of insert, delete, insertFromRefs or deleteFromRefs must be provided"
        }
        require(insertFromRefs.isEmpty() || (insert.isEmpty() && delete.isEmpty())) {
            "insertFromRefs cannot be combined with regular insert or delete"
        }
        require(deleteFromRefs.isEmpty() || (insert.isEmpty() && delete.isEmpty())) {
            "deleteFromRefs cannot be combined with regular insert or delete"
        }
    }
}

@GenerateNoArgConstructor
data class QueryRequest(
    val context : Map<String, Any> = emptyMap(),
    val podId: String,
    val query: String,
    val variables: Map<String, Any>? = null,
    val operationName: String? = null,
    val targetGraphs: Set<String> = emptySet(),
    val predefinedSchema: String? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class QueryResult(
    val data: Map<String, Any>? = null,
    val errors: List<Map<String, Any>>? = null
) {

    fun toJsonLD(context: Map<String, Any>): Map<String, Any> {
        return transformKeys(data, context)?.let { nonNullGraph ->
            // Compact data coming from GraphQL using context
            JsonLdProcessor.compact(JsonLdProcessor.expand(nonNullGraph), context, JsonLdOptions())
        }?: emptyMap()
    }

    private fun transformKeys(graphQLData: Any?, context: Map<String, Any>): Any? {
        return when (graphQLData) {
            null -> null
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
    @JsonProperty(JsonLdKeywords.id)
    val id: String,
    @JsonProperty(JsonLdKeywords.context)
    val context: Map<String, Any>,
    @JsonProperty(KvasirVocab.podId)
    val podId: String,
    @JsonProperty(KvasirVocab.name)
    val name: String,
    @JsonProperty(KvasirVocab.description)
    val description: String,
    @JsonProperty(KvasirVocab.schema)
    val schema: String,
    @JsonProperty(KvasirVocab.targetGraphs)
    val targetGraphs: Set<String> = emptySet()
)

data class SliceSummary(
    @JsonProperty(JsonLdKeywords.id)
    val id: String,
    @JsonProperty(KvasirVocab.name)
    val name: String,
    @JsonProperty(KvasirVocab.description)
    val description: String
)

data class RDFStatement(
    val subject: String,
    val predicate: String,
    val `object`: Any,
    val graph: String = "",
    val dataType: String? = null,
    val language: String? = null
)
