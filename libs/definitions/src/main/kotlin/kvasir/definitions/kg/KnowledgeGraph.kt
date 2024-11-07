package kvasir.definitions.kg

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor
import io.prometheus.client.Predicate
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.kg.changeops.Assertion
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import java.time.Instant
import java.util.*

interface KnowledgeGraph {

    fun process(request: ChangeRequest): Uni<Void>

    fun query(request: QueryRequest): Uni<QueryResult>

    fun listChanges(request: ChangeHistoryRequest): Uni<List<ChangeReport>>

    fun getChange(request: ChangeHistoryRequest): Uni<ChangeReport?>

    fun getChangeRecords(request: ChangeHistoryRequest): Uni<List<ChangeRecord>>

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

    fun loadReference(podId: String, targetGraph: String, reference: Map<String, Any>): Multi<RDFStatement>
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

data class ChangeResult(
    /**
     * The unique identifier of the Change Request that resulted in this Change Result.
     */
    @JsonProperty(JsonLdKeywords.id)
    val id: String,
    /**
     * The context that was used to produce the Change Request.
     */
    @JsonProperty(JsonLdKeywords.context)
    val context: Map<String, Any> = emptyMap(),
    /**
     * The unique identifier of the Pod where the Change Request was applied.
     */
    @JsonProperty(KvasirVocab.podId)
    val podId: String,
    /**
     * The unique identifier of the Slice where the Change Request was applied.
     */
    @JsonProperty(KvasirVocab.sliceId)
    val sliceId: String? = null,
    /**
     * The status of the Change Request.
     */
    @JsonProperty(KvasirVocab.code)
    val code: ChangeResultCode,
    /**
     * A Change Result can be chunked when it is too large to transfer as single message.
     * The hasNextChunk flag indicates whether there are more chunks to follow.
     */
    @JsonProperty(KvasirVocab.hasNextChunk)
    val hasNextChunk: Boolean = false,
    /**
     * A Change Result can be chunked when it is too large to transfer as single message.
     * The seqNr is used to identify the chunks.
     */
    @JsonProperty(KvasirVocab.sequenceNumber)
    val seqNr: Long = 0,
    /**
     * The JSON-LD instances that were inserted as a result of the Change Request.
     */
    @JsonProperty(KvasirVocab.insert)
    val insert: List<Map<String, Any>>,
    /**
     * The JSON-LD instances that were deleted as a result of the Change Request.
     */
    @JsonProperty(KvasirVocab.delete)
    val delete: List<Map<String, Any>>,
    /**
     * If the result code is not COMMITTED, this field may contain additional information on the nature of why
     * the Change Request was not applied.
     */
    @JsonProperty(KvasirVocab.error)
    val errors: List<Map<String, Any>>? = null
) {

    companion object {
        fun success(
            request: ChangeRequest,
            effectiveDeletes: List<Map<String, Any>>,
            effectiveInserts: List<Map<String, Any>>
        ): ChangeResult {
            return ChangeResult(
                id = request.id,
                context = request.context,
                podId = request.podId,
                sliceId = request.sliceId,
                code = ChangeResultCode.COMMITTED,
                insert = effectiveInserts,
                delete = effectiveDeletes
            )
        }

        fun error(request: ChangeRequest, status: ChangeResultCode, errors: List<Map<String, Any>>): ChangeResult {
            return ChangeResult(
                id = request.id,
                context = request.context,
                podId = request.podId,
                sliceId = request.sliceId,
                code = status,
                insert = emptyList(),
                delete = emptyList(),
                errors = errors
            )
        }

    }

}

@JsonInclude(JsonInclude.Include.NON_DEFAULT)
data class ChangeReport(
    val id: String,
    val timestamp: Instant,
    val resultCode: ChangeResultCode,
    val sliceId: String? = null,
    val nrOfInserts: Long = 0,
    val nrOfDeletes: Long = 0,
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
    val changeRequestId: String? = null
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
        } ?: emptyMap()
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

enum class ChangeRecordType {
    INSERT, DELETE
}

interface ChangeResultSliceFilter : Predicate<List<Map<String, Any>>> {

    fun podId(): String

    fun sliceId(): String

}