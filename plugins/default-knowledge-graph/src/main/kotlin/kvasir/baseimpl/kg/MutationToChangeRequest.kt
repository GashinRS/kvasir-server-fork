package kvasir.baseimpl.kg

import graphql.Scalars
import graphql.language.*
import graphql.scalars.ExtendedScalars
import graphql.schema.*
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.kg.changes.ChangeRequest
import kvasir.definitions.kg.graphql.ARG_REVERSE_NAME
import kvasir.definitions.kg.graphql.DIRECTIVE_PREDICATE_NAME
import kvasir.definitions.kg.graphql.FIELD_ID_NAME
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.XSDVocab
import kvasir.utils.graphql.getFQName
import kvasir.utils.graphql.innerType
import kvasir.utils.idgen.ChangeRequestId
import kvasir.utils.rdf.RDFTransformer
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.OffsetTime

class MutationToChangeRequest(private val request: QueryRequest) {

    private val mutationFields = mutableListOf<Field>()
    val changeRequestId = ChangeRequestId.generate(request.requestingUser).encode()

    private val inserts = mutableListOf<Map<String, Any>>()
    private val deletes = mutableListOf<Map<String, Any>>()

    fun add(env: DataFetchingEnvironment) {
        try {
            mutationFields.add(env.field)
            val instances = env.field.arguments.associateWith { env.fieldDefinition.getArgument(it.name) }
                .map { (argument, argumentDefinition) -> parseArgumentValue(env, argumentDefinition, argument.value) }
                .filterNotNull().flatten()
            if (env.field.name.startsWith("add") || env.field.name.startsWith("insert")) {
                inserts.addAll(instances)
            }
            if (env.field.name.startsWith("remove") || env.field.name.startsWith("delete")) {
                deletes.addAll(instances)
            }
        } catch (e: Throwable) {
            throw IllegalArgumentException("Failed to parse mutation field '${env.field.name}': ${e.message}", e)
        }
    }

    fun isComplete(env: DataFetchingEnvironment): Boolean {
        val totalOps = env.document.definitions.filterIsInstance<OperationDefinition>()
            .firstOrNull { it.operation == OperationDefinition.Operation.MUTATION }?.selectionSet?.selections?.size
        return totalOps == mutationFields.size
    }

    fun getChangeRequest(): ChangeRequest {
        return ChangeRequest(
            id = changeRequestId,
            context = request.context,
            requestingUser = request.requestingUser,
            podId = request.podId,
            sliceId = request.sliceId,
            sliceTag = request.sliceTag,
            insert = inserts,
            delete = deletes
        )
    }

    private fun parseArgumentValue(
        env: DataFetchingEnvironment,
        argumentDefinition: GraphQLArgument,
        argValue: Value<*>
    ): List<JSONObject>? {
        return when (argValue) {
            // Separately handle variable references
            is VariableReference -> {
                val value = env.variables[argValue.name]
                when (value) {
                    is Map<*, *> -> listOf(contextualizeJson(value as JSONObject, argumentDefinition.type.innerType()))
                    is Iterable<*> -> value.map {
                        contextualizeJson(
                            it as JSONObject,
                            argumentDefinition.type.innerType()
                        )
                    }

                    else -> null
                }
            }

            is ObjectValue -> listOf(toJSON(argValue, argumentDefinition.type.innerType()))
            is ArrayValue -> argValue.values.map {
                toJSON(
                    it as ObjectValue,
                    argumentDefinition.type.innerType()
                )
            }

            else -> null
        }
    }

    /**
     * Converts a GraphQL ObjectValue to a JSON object, using the provided GraphQLInputObjectType to resolve field names.
     * The resulting JSON object is suitable for inclusion in a ChangeRequest.
     */
    private fun toJSON(objectValue: ObjectValue, type: GraphQLInputObjectType): JSONObject {
        val typeFqName = getFQName(type, request.context)
        return objectValue.objectFields.associate { field ->
            val fieldDefinition = type.getField(field.name)
            val rawValue = field.value
            if (field.name == FIELD_ID_NAME) {
                toIDReference(rawValue)
            } else {
                // Handling for reverse
                val reverse =
                    fieldDefinition.getAppliedDirective(DIRECTIVE_PREDICATE_NAME)?.getArgument(ARG_REVERSE_NAME)
                        ?.getValue<Boolean>() ?: false
                val output = getFQName(type.getField(field.name), request.context) to if (rawValue is ArrayValue) {
                    rawValue.values.map { listRawValue ->
                        singleValueToJSON(listRawValue, fieldDefinition)
                    }
                } else {
                    singleValueToJSON(rawValue, fieldDefinition)
                }
                if (reverse) {
                    JsonLdKeywords.reverse to mapOf(output)
                } else {
                    output
                }
            }
        }.plus(JsonLdKeywords.type to typeFqName)
    }

    private fun singleValueToJSON(rawValue: Value<*>, encapsulatingFieldDefinition: GraphQLInputObjectField): Any {
        return if (rawValue is ScalarValue<*>) {
            val scalarType = encapsulatingFieldDefinition.type.innerType<GraphQLScalarType>()
            convertScalar(rawValue, scalarType)
        } else {
            toJSON(
                rawValue as ObjectValue,
                encapsulatingFieldDefinition.type.innerType()
            )
        }
    }

    /**
     * Contextualizes a JSON object provided as variable input for the Query, to make it suitable for inclusion in the ChangeRequest.
     */
    private fun contextualizeJson(value: JSONObject, type: GraphQLInputObjectType): JSONObject {
        val typeFqName = getFQName(type, request.context)
        return value.entries.associate { (fieldName, fieldValue) ->
            val fieldDefinition = type.getField(fieldName)
            val rawValue = fieldValue
            if (fieldName == FIELD_ID_NAME) {
                toIDReference(rawValue)
            } else {
                val reverse =
                    fieldDefinition.getAppliedDirective(DIRECTIVE_PREDICATE_NAME)?.getArgument(ARG_REVERSE_NAME)
                        ?.getValue<Boolean>() ?: false
                val output = getFQName(type.getField(fieldName), request.context) to if (rawValue is Iterable<*>) {
                    rawValue.map { listRawValue ->
                        contextualizeSingleValue(listRawValue!!, fieldDefinition)
                    }
                } else {
                    contextualizeSingleValue(rawValue, fieldDefinition)
                }
                if (reverse) {
                    JsonLdKeywords.reverse to mapOf(output)
                } else {
                    output
                }
            }
        }.plus(JsonLdKeywords.type to typeFqName)
    }

    private fun contextualizeSingleValue(rawValue: Any, encapsulatingFieldDefinition: GraphQLInputObjectField): Any {
        return if (rawValue is Map<*, *>) {
            contextualizeJson(
                rawValue as JSONObject,
                encapsulatingFieldDefinition.type.innerType()
            )
        } else {
            val scalarType = encapsulatingFieldDefinition.type.innerType<GraphQLScalarType>()
            convertJSONScalar(rawValue, scalarType)
        }
    }

    private fun toIDReference(rawValue: Any): Pair<String, String> {
        val rawValueStr = if (rawValue is StringValue) rawValue.value else rawValue as String
        val ref = JsonLdHelper.getFQName(rawValueStr, request.context) ?: rawValueStr
        // Check if the ref is a valid URI
        return JsonLdKeywords.id to RDFTransformer.ensureValidAbsoluteIri(ref)
    }

    private fun convertScalar(value: ScalarValue<*>, type: GraphQLScalarType): Any {
        return when {
            type == Scalars.GraphQLID -> mapOf(toIDReference(value))
            type == Scalars.GraphQLBoolean && value is BooleanValue -> value.isValue
            type == Scalars.GraphQLFloat && value is FloatValue -> value.value
            type == Scalars.GraphQLInt && value is IntValue -> value.value
            type == Scalars.GraphQLString && value is StringValue -> value.value
            type.name == ExtendedScalars.DateTime.name && value is StringValue -> mapOf(
                JsonLdKeywords.type to XSDVocab.dateTime,
                JsonLdKeywords.value to value.value
            )

            type.name == ExtendedScalars.Date.name && value is StringValue -> mapOf(
                JsonLdKeywords.type to XSDVocab.date,
                JsonLdKeywords.value to value.value
            )

            type.name == ExtendedScalars.Time.name && value is StringValue -> mapOf(
                JsonLdKeywords.type to XSDVocab.time,
                JsonLdKeywords.value to value.value
            )

            else -> throw IllegalArgumentException("Unsupported scalar value type: ${value.javaClass}")
        }
    }

    private fun convertJSONScalar(value: Any, type: GraphQLScalarType): Any {
        return when {
            type == Scalars.GraphQLID -> mapOf(toIDReference(value))
            type == Scalars.GraphQLBoolean && value is Boolean -> value
            type == Scalars.GraphQLFloat && (value is Float || value is Double) -> value
            type == Scalars.GraphQLInt && (value is Int || value is Long) -> value
            type == Scalars.GraphQLString && value is String -> value
            type.name == ExtendedScalars.DateTime.name && (value is String || value is OffsetDateTime) -> mapOf(
                JsonLdKeywords.type to XSDVocab.dateTime,
                JsonLdKeywords.value to value
            )

            type.name == ExtendedScalars.Date.name && (value is String || value is LocalDate) -> mapOf(
                JsonLdKeywords.type to XSDVocab.date,
                JsonLdKeywords.value to value
            )

            type.name == ExtendedScalars.Time.name && (value is String || value is OffsetTime) -> mapOf(
                JsonLdKeywords.type to XSDVocab.time,
                JsonLdKeywords.value to value
            )

            else -> throw IllegalArgumentException("Unsupported scalar value type: ${value.javaClass}")
        }
    }

}