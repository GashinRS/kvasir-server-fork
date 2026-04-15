package kvasir.definitions.kg

import com.fasterxml.jackson.annotation.JsonInclude
import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.kg.changes.ChangeRequest
import kvasir.definitions.kg.changes.ProcessedChange
import kvasir.definitions.kg.changes.Reference
import kvasir.definitions.kg.graphql.*
import kvasir.definitions.rdf.*
import java.time.Instant

const val DEFAULT_PAGE_SIZE = 100

interface KnowledgeGraph {

    /**
     * Convenience function: introspects the request and forwards it to the appropriate processing function,
     * depending on if it is state-dependent, contains references that should be resolved, etc.
     *
     * @param request The change request to process
     * @return A Uni emitting the ChangeReport once processing is complete
     */
    fun process(request: ChangeRequest): Uni<ProcessedChange> = when {
        request.isStateDependent() -> processStateDependent(request)
        request.insertFromRefs.isNotEmpty() || request.deleteFromRefs.isNotEmpty() -> processReferenced(request)
        else -> processPlain(setOf(request)).map { it.first() }
    }

    /**
     * Process change requests that express only basic inserts/deletes.
     * Feeding this method a change request with assertions, S3 references, with-clauses, will result in an exception!
     * All requests must target the same Pod, otherwise an exception is produced via the returned Uni.
     *
     * @param requests The collection of change requests to process
     * @return A Uni emitting the collection of ChangeReports once processing is complete
     */
    fun processPlain(requests: Collection<ChangeRequest>): Uni<Collection<ProcessedChange>>

    /**
     * Process a state-dependent change request
     * (A request having assertions, S3-references, with-clauses)
     *
     * @param request The change request to process
     * @return A Uni emitting the ChangeReport once processing is complete
     */
    fun processStateDependent(request: ChangeRequest): Uni<ProcessedChange>

    /**
     * Process a change request that contains a reference (e.g. S3)
     * Processing these changes may be long-running (depending on the ref size)
     *
     * @param request The change request to process
     * @return A Uni emitting the ChangeReport once processing is complete
     */
    fun processReferenced(request: ChangeRequest): Uni<ProcessedChange>

    fun query(request: QueryRequest): Multi<QueryResult>

    fun getChangeRecords(request: ChangeRecordRequest): Uni<PagedResult<ChangeRecord>>

    fun streamChangeRecords(request: ChangeRecordRequest): Multi<ChangeRecord>

    fun rollback(request: ChangeRollbackRequest): Uni<Void>
}

interface ReferenceLoader {

    fun isSupported(reference: Reference): Boolean

    fun loadReference(podOrSliceId: String, reference: Reference): Multi<RDFStatement>
}

data class ChangeRollbackRequest(
    val podId: String,
    val changeId: String
)

@GenerateNoArgConstructor
data class QueryRequest(
    val context: Map<String, Any> = emptyMap(),
    val requestingUser: String,
    val podId: String,
    val sliceId: String? = null,
    val query: String,
    val variables: Map<String, Any>? = null,
    val operationName: String? = null,
    val predefinedSchema: String? = null,
    val atTimestamp: Instant? = null,
    val atChangeId: String? = null
)

data class ChangeRecordRequest(
    val podId: String,
    val changeId: String,
    var cursor: String? = null,
    val pageSize: Int = 100,
    // Optional subject filter (subject must be in the supplied set)
    val subjectIn: Set<String>? = null,
    // Optional predicate filter (predicate must be in the supplied set)
    val predicateIn: Set<String>? = null,
    // Optional object filter (object must be in the supplied set)
    val objectIn: Set<Any>? = null,
    // Optional graph filter (graph must be in the supplied set)
    val graphIn: Set<String>? = null,
    // Optional type filter (which type of records to include in the result, inserts, deletes or both)
    val recordType: ChangeRecordType? = null
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
            is Map<*, *> -> {
                val transformedMap = graphQLData
                    .mapValues { (key, value) ->
                        if (key == FIELD_TYPENAME_NAME) {
                            JsonLdHelper.getFQName(value as String, context, "_")
                        } else {
                            transform(value!!, context)
                        }
                    }
                    .mapKeys { e ->
                        val key = e.key as String
                        if (key == TYPE_RESOURCE) {
                            return@mapKeys RDFSVocab.Resource
                        }
                        if (key == FIELD_ID_NAME) {
                            return@mapKeys JsonLdKeywords.id
                        }
                        if (key == FIELD_TYPES_NAME || key == FIELD_TYPENAME_NAME) {
                            return@mapKeys JsonLdKeywords.type
                        }
                        val keyPrefix = key.substringBefore("_")
                        if (context.contains(keyPrefix)) {
                            key.replaceFirst(keyPrefix.plus("_"), context[keyPrefix] as String)
                        } else {
                            key
                        }
                    }
                if (transformedMap.containsKey(FIELD_RAW_RDF_NAME)) {
                    val rawRDF = transformedMap[FIELD_RAW_RDF_NAME] as Map<String, Any>
                    transformedMap.minus(FIELD_RAW_RDF_NAME).plus(rawRDF)
                } else {
                    transformedMap
                }
            }

            is Collection<*> -> graphQLData.map { transform(it!!, context) }

            else -> graphQLData
        }
    }
}

data class ChangeRecord(
    val changeId: String,
    val timestamp: Instant,
    val type: ChangeRecordType,
    val statement: RDFStatement
)

@GenerateNoArgConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChangeRecords(
    val context: Map<String, Any>,
    val id: String,
    val timestamp: Instant,
    val delete: JSONObject? = null,
    val insert: JSONObject? = null
)

enum class ChangeRecordType {
    INSERT, DELETE
}

enum class QueryRequestStatusCode {
    COMPLETED, FAILED
}

@GenerateNoArgConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
data class QueryRequestEvent(
    val id: String,
    val timestamp: Instant,
    val context: Map<String, Any> = emptyMap(),
    val requestingUser: String,
    val statusCode: QueryRequestStatusCode,
    val podId: String,
    val sliceId: String? = null,
    val query: String,
    val variables: Map<String, Any>? = null,
    val operationName: String? = null,
    val atTimestamp: Instant? = null,
    val atChangeId: String? = null,
    val message: String? = null
) {
    companion object {

        fun fromQueryRequest(
            queryId: String,
            queryRequest: QueryRequest,
            resultCode: QueryRequestStatusCode,
            errorMessage: String? = null,
            timestamp: Instant = Instant.now(),
        ): QueryRequestEvent {
            return QueryRequestEvent(
                id = queryId,
                timestamp = timestamp,
                context = queryRequest.context,
                requestingUser = queryRequest.requestingUser,
                statusCode = resultCode,
                podId = queryRequest.podId,
                sliceId = queryRequest.sliceId,
                query = queryRequest.query,
                variables = queryRequest.variables,
                operationName = queryRequest.operationName,
                atTimestamp = queryRequest.atTimestamp ?: timestamp.takeIf { queryRequest.atChangeId == null },
                atChangeId = queryRequest.atChangeId,
                message = errorMessage
            )
        }

    }
}

interface TypeRegistry {

    fun getTypeInfo(podId: String): Uni<List<KGType>>

}

data class KGType(
    val uri: String,
    val properties: List<KGProperty>
)

data class KGProperty(
    val uri: String,
    val typeRefs: Set<KGTypeReference>
)

enum class KGPropertyKind {
    Literal, IRI
}

data class KGTypeReference(
    val kind: KGPropertyKind,
    val name: String
)

data class PagedResult<T>(
    val items: List<T>,
    val nextCursor: String? = null,
    val previousCursor: String? = null,
    val totalCount: Long? = null
)