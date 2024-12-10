package kvasir.definitions.kg

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor
import com.github.jsonldjava.shaded.com.google.common.hash.Hashing
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.kg.changeops.Assertion
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirNamedGraphs
import kvasir.definitions.rdf.KvasirVocab
import java.time.Instant
import java.util.UUID
import java.util.function.Predicate

interface KnowledgeGraph {

    fun process(request: ChangeRequest): Uni<Void>

    fun query(request: QueryRequest): Uni<QueryResult>

    fun listChanges(request: ChangeHistoryRequest): Uni<PagedResult<ChangeReport>>

    fun getChange(request: ChangeHistoryRequest): Uni<ChangeReport?>

    fun getChangeRecords(request: ChangeHistoryRequest): Uni<PagedResult<ChangeRecord>>

    fun streamChangeRecords(request: ChangeHistoryRequest): Multi<ChangeRecord>

    fun rollback(request: ChangeRollbackRequest): Uni<Void>

}

interface SliceStore {

    fun persist(segment: Slice): Uni<Void>

    fun list(podId: String): Uni<List<SliceSummary>>

    fun getById(podId: String, segmentId: String): Uni<Slice?>

    fun deleteById(podId: String, segmentId: String): Uni<Void>

    fun loadFilterById(podId: String, segmentId: String): Uni<ChangeResultSliceFilter?>

    fun loadAllFilters(podId: String): Uni<Set<ChangeResultSliceFilter>>
}

interface ReferenceLoader {

    fun isSupported(reference: Map<String, Any>): Boolean

    fun loadReference(podOrSliceId: String, reference: Map<String, Any>): Multi<RDFStatement>
}

data class ChangeRequest(
    /**
     * The unique identifier of the Change Request.
     */
    val id: String,
    /**
     * The context used to produce the Change Request.
     */
    val context: Map<String, Any> = emptyMap(),
    /**
     * The unique identifier of the Pod where the Change Request should be applied.
     */
    val podId: String,
    /**
     * The unique identifier of the Slide where the Change Request should be applied.
     */
    val sliceId: String? = null,
    /**
     * The Change Request will only be applied if all assertions resolve to true.
     */
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
        require(insert.filterIsInstance<String>().isEmpty() || with != null) {
            "Insert templates require a with-clause"
        }
        require(delete.filterIsInstance<String>().isEmpty() || with != null) {
            "Delete templates require a with-clause"
        }
    }

    companion object {
        const val URN_PREFIX = "kvasir:change:"
    }
}

enum class ChangeResultCode {
    /**
     * The Change Request was successfully applied.
     */
    COMMITTED,

    /**
     * The Change Request was not applied because one or more assertions failed.
     */
    ASSERTION_FAILED,

    /**
     * The Change Request was not applied because the with-clause did not return any results.
     */
    NO_MATCHES,

    /**
     * The Change Request was not applied because the with-clause returned too many results.
     */
    TOO_MANY_MATCHES,

    /**
     * The Change Request was not applied because of a validation error.
     */
    VALIDATION_ERROR,

    /**
     * The Change Request was not applied because of an internal error.
     */
    INTERNAL_ERROR
}

data class ChangeRollbackRequest(
    val podId: String,
    val changeRequestId: String
)

@JsonInclude(JsonInclude.Include.NON_DEFAULT)
data class ChangeReport(
    @JsonProperty(JsonLdKeywords.id)
    val id: String,
    @JsonProperty(KvasirVocab.podId)
    val podId: String,
    @JsonProperty(KvasirVocab.timestamp)
    val timestamp: Instant,
    @JsonProperty(KvasirVocab.resultCode)
    val resultCode: ChangeResultCode,
    @JsonProperty(KvasirVocab.sliceId)
    val sliceId: String? = null,
    @JsonProperty(KvasirVocab.nrOfInserts)
    val nrOfInserts: Long = 0,
    @JsonProperty(KvasirVocab.nrOfDeletes)
    val nrOfDeletes: Long = 0,
    @JsonProperty(KvasirVocab.errorMessage)
    val errorMessage: String? = null
)

@GenerateNoArgConstructor
data class QueryRequest(
    val context: Map<String, Any> = emptyMap(),
    val podId: String,
    val query: String,
    val variables: Map<String, Any>? = null,
    val operationName: String? = null,
    val targetGraphs: Set<String> = emptySet(),
    val predefinedSchema: String? = null,
    val atTimestamp: Instant? = null,
    val atChangeRequestId: String? = null
)

data class ChangeHistoryRequest(
    val podId: String,
    val sliceId: String? = null,
    val fromTimestamp: Instant? = null,
    val toTimestamp: Instant? = null,
    val changeRequestId: String? = null,
    val cursor: String? = null,
    val pageSize: Int = 100
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class QueryResult(
    val data: Map<String, Any>? = null,
    val errors: List<Map<String, Any>>? = null,
    val extensions: Map<String, Any>? = null
) {

    fun toJsonLD(context: Map<String, Any>): List<Map<String, Any>> {
        val dataAsGraph = transform(data, context)?.let {
            when (it) {
                is List<*> -> mapOf(JsonLdKeywords.graph to it)
                else -> mapOf(JsonLdKeywords.graph to listOf(it))
            }
        }
        return listOfNotNull(
            // Compact data coming from GraphQL using context
            dataAsGraph?.let {
                JsonLdProcessor.compact(
                    JsonLdProcessor.expand(
                        mapOf(
                            JsonLdKeywords.id to KvasirNamedGraphs.queryResultDataGraph,
                            JsonLdKeywords.context to KvasirVocab.context
                        ) + it
                    ),
                    context,
                    JsonLdOptions()
                ) as Map<String, Any>
            },
            extensions?.get("pagination")?.let { it as List<Map<String, Any>> }?.takeIf { it.isNotEmpty() }?.let {
                mapOf(
                    JsonLdKeywords.context to KvasirVocab.context,
                    JsonLdKeywords.id to KvasirNamedGraphs.queryResultPaginationGraph,
                    JsonLdKeywords.graph to it
                )
            },
            errors?.takeIf { it.isNotEmpty() }?.let {
                mapOf(
                    JsonLdKeywords.context to KvasirVocab.context,
                    JsonLdKeywords.id to KvasirNamedGraphs.queryResultErrorsGraph,
                    JsonLdKeywords.graph to it
                )
            },
        )
    }

    private fun transform(graphQLData: Any?, context: Map<String, Any>): Any? {
        return when (graphQLData) {
            null -> null
            is Map<*, *> -> graphQLData.mapKeys { e ->
                val key = e.key as String
                if (key == "id") {
                    return@mapKeys JsonLdKeywords.id
                }
                if (key == "__typename") {
                    return@mapKeys JsonLdKeywords.type
                }
                val keyPrefix = key.substringBefore("_")
                if (context.contains(keyPrefix)) {
                    key.replaceFirst(keyPrefix.plus("_"), context[keyPrefix] as String)
                } else {
                    key
                }
            }
                .mapValues { (key, value) ->
                    if (key == JsonLdKeywords.type) {
                        JsonLdHelper.getFQName(value as String, context, "_")
                    } else {
                        transform(value!!, context)
                    }
                }

            is Collection<*> -> graphQLData.map { transform(it!!, context) }

            else -> graphQLData
        }
    }
}

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
    @JsonProperty(KvasirVocab.shacl)
    val shacl: String,
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

enum class SliceEventType {
    CREATED,
    UPDATED,
    DELETED
}

data class SliceEvent(
    val podId: String,
    val sliceId: String,
    val eventType: SliceEventType
)

@JsonInclude(JsonInclude.Include.NON_DEFAULT)
data class RDFStatement(
    val subject: String,
    val predicate: String,
    val `object`: Any,
    val graph: String = "",
    val dataType: String? = null,
    val language: String? = null
)

data class ChangeRecord(
    val changeRequestId: String,
    val timestamp: Instant,
    val type: ChangeRecordType,
    val statement: RDFStatement
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChangeRecords(
    @JsonProperty(JsonLdKeywords.context)
    val context: Map<String, Any>,
    @JsonProperty(JsonLdKeywords.id)
    val id: String,
    @JsonProperty(KvasirVocab.timestamp)
    val timestamp: Instant,
    @JsonProperty(KvasirVocab.delete)
    val deleted: Any?,
    @JsonProperty(KvasirVocab.insert)
    val inserted: Any?
)

enum class ChangeRecordType {
    INSERT, DELETE
}

interface ChangeResultSliceFilter : Predicate<List<Map<String, Any>>> {

    fun podId(): String

    fun sliceId(): String

}

data class PagedResult<T>(
    val items: List<T>,
    val nextCursor: String? = null,
    val previousCursor: String? = null,
    val totalCount: Long? = null
)