package kvasir.definitions.kg

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.kg.changes.Assertion
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirNamedGraphs
import kvasir.definitions.rdf.KvasirVocab
import java.time.Instant

const val DEFAULT_PAGE_SIZE = 100

interface KnowledgeGraph {

    fun process(request: ChangeRequest): Uni<Void>

    fun query(request: QueryRequest): Uni<QueryResult>

    fun getChangeRecords(request: ChangeRecordRequest): Uni<PagedResult<ChangeRecord>>

    fun streamChangeRecords(request: ChangeRecordRequest): Multi<ChangeRecord>

    fun rollback(request: ChangeRollbackRequest): Uni<Void>

}

interface ReferenceLoader {

    fun isSupported(reference: Map<String, Any>): Boolean

    fun loadReference(podOrSliceId: String, reference: Map<String, Any>): Multi<RDFStatement>
}

@GenerateNoArgConstructor
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

enum class ChangeStatusCode {
    /**
     * The Change Request was added to the processing queue
     */
    QUEUED,

    /**
     * The Change Request has been preprocessed by the configured preprocessing chain.
     */
    PREPROCESSED,

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

@GenerateNoArgConstructor
data class QueryRequest(
    val context: Map<String, Any> = emptyMap(),
    val podId: String,
    val sliceId: String? = null,
    val query: String,
    val variables: Map<String, Any>? = null,
    val operationName: String? = null,
    val targetGraphs: Set<String> = emptySet(),
    val predefinedSchema: String? = null,
    val atTimestamp: Instant? = null,
    val atChangeRequestId: String? = null
)

data class ChangeRecordRequest(
    val podId: String,
    val changeRequestId: String,
    var cursor: String? = null,
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

@GenerateNoArgConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChangeRecords(
    @JsonProperty(JsonLdKeywords.context)
    val context: Map<String, Any>,
    @JsonProperty(JsonLdKeywords.id)
    val id: String,
    @JsonProperty(KvasirVocab.timestamp)
    val timestamp: Instant,
    @JsonProperty(KvasirVocab.delete)
    val deleted: Any? = null,
    @JsonProperty(KvasirVocab.insert)
    val inserted: Any? = null
)

enum class ChangeRecordType {
    INSERT, DELETE
}


interface TypeRegistry {

    fun getTypeInfo(podId: String): Uni<List<KGType>>

}

data class MetadataEntry(
    val typeUri: String,
    val propertyUri: String,
    val propertyKind: KGPropertyKind,
    val propertyRef: String
)

data class KGType(
    val uri: String,
    val properties: List<KGProperty>
)

data class KGProperty(
    val uri: String,
    val kind: KGPropertyKind,
    val typeRefs: Set<String>
)

enum class KGPropertyKind {
    Literal, IRI
}

data class PagedResult<T>(
    val items: List<T>,
    val nextCursor: String? = null,
    val previousCursor: String? = null,
    val totalCount: Long? = null
)