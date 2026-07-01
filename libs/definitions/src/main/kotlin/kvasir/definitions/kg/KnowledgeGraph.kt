package kvasir.definitions.kg

import com.fasterxml.jackson.annotation.JsonInclude
import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.TypeName
import graphql.schema.idl.SchemaParser
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

    fun finalize(request: ChangeFinalizeRequest): Uni<Void>

    fun rollback(request: ChangeRollbackRequest): Uni<Void>
}

interface ReferenceLoader {

    fun isSupported(reference: Reference): Boolean

    fun loadReference(podOrSliceId: String, reference: Reference): Multi<RDFStatement>
}

data class ChangeFinalizeRequest(
    val podId: String,
    val changeId: String
)

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
    val sliceTag: String? = null,
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

    /**
     * Converts this GraphQL query result into a JSON-LD representation.
     *
     * @param context The JSON-LD context to apply (prefix map).
     * @param schema Optional GraphQL SDL string. When provided, field types are resolved from the schema
     *   so that scalar values are mapped correctly — e.g. `ID` fields become `{"@id": "..."}` instead of
     *   plain strings, and temporal scalars (`DateTime`, `Date`, `Time`) become typed JSON-LD value objects.
     *   When `null`, all scalar leaf values are passed through as-is (legacy behaviour).
     */
    fun toJsonLD(context: Map<String, Any>, schema: String? = null): List<Map<String, Any>> {
        val fieldTypeMap = schema?.let { buildFieldTypeMap(it) }
        val dataAsGraph = transform(data, context, TYPE_QUERY, fieldTypeMap)?.let {
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

    /**
     * Builds a two-level map: `typeName → fieldName → scalarTypeName` from a GraphQL SDL string.
     * Only fields whose base type resolves to a known scalar (or any leaf type name) are recorded.
     * Parsing errors are silently swallowed, returning an empty map.
     */
    private fun buildFieldTypeMap(schema: String): Map<String, Map<String, String>> {
        return try {
            SchemaParser().parse(schema).types().values
                .filterIsInstance<ObjectTypeDefinition>()
                .associate { typeDef ->
                    typeDef.name to typeDef.fieldDefinitions.mapNotNull { field ->
                        unwrapBaseTypeName(field.type)?.let { field.name to it }
                    }.toMap()
                }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Unwraps `NonNull` and `List` wrappers from a GraphQL type to reach the named base type. */
    private fun unwrapBaseTypeName(type: graphql.language.Type<*>): String? {
        return when (type) {
            is NonNullType -> unwrapBaseTypeName(type.type)
            is ListType -> unwrapBaseTypeName(type.type)
            is TypeName -> type.name
            else -> null
        }
    }

    /**
     * Recursively transforms a GraphQL query result value into a JSON-LD–compatible structure.
     *
     * @param graphQLData The current node value from the GraphQL result.
     * @param context The JSON-LD prefix map.
     * @param parentTypeName The GraphQL type name of the object that owns the current node (used to look up field types).
     * @param fieldTypeMap Lookup table produced by [buildFieldTypeMap], or `null` when no schema is available.
     */
    private fun transform(
        graphQLData: Any?,
        context: Map<String, Any>,
        parentTypeName: String? = null,
        fieldTypeMap: Map<String, Map<String, String>>? = null
    ): Any? {
        return when (graphQLData) {
            null -> null
            is Map<*, *> -> {
                val transformedMap = graphQLData
                    .mapValues { (key, value) ->
                        val keyStr = key as String
                        if (keyStr == FIELD_TYPENAME_NAME) {
                            JsonLdHelper.getFQName(value as String, context, "_")
                        } else {
                            // Determine the declared field type from the schema (if available)
                            val fieldTypeName = fieldTypeMap?.get(parentTypeName)?.get(keyStr)
                            if (keyStr != FIELD_ID_NAME && fieldTypeName != null && isGraphQLScalarName(fieldTypeName) && value != null) {
                                // Known scalar field: apply type-aware JSON-LD conversion
                                when (value) {
                                    is Collection<*> -> value.map { item ->
                                        if (item != null) convertResultScalarToJsonLd(item, fieldTypeName) else null
                                    }

                                    else -> convertResultScalarToJsonLd(value, fieldTypeName)
                                }
                            } else {
                                // Object type (or unknown): recurse, carrying the child type name forward
                                val childTypeName = fieldTypeName?.takeUnless { isGraphQLScalarName(it) }
                                transform(value, context, childTypeName, fieldTypeMap)
                            }
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

            // For collections of objects, carry the parent type name forward so each element
            // can resolve its fields against the correct type in the schema.
            is Collection<*> -> graphQLData.map { transform(it!!, context, parentTypeName, fieldTypeMap) }

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
    val sliceTag: String? = null,
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
                sliceTag = queryRequest.sliceTag,
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