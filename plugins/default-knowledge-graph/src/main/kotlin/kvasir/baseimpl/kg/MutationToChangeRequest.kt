package kvasir.baseimpl.kg

import graphql.Scalars
import graphql.language.*
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLInputObjectType
import kvasir.definitions.kg.ChangeRequest
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.kg.graphql.FIELD_ID_NAME
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.utils.graphql.getFQName
import kvasir.utils.graphql.innerType
import kvasir.utils.idgen.ChangeRequestId

class MutationToChangeRequest(private val request: QueryRequest) {

    private val mutationFields = mutableListOf<Field>()
    private val changesBaseUri = (request.sliceId ?: request.podId) + "/changes"
    val changeRequestId = ChangeRequestId.generate(changesBaseUri).encode()

    private val inserts = mutableListOf<Map<String, Any>>()
    private val deletes = mutableListOf<Map<String, Any>>()

    fun add(env: DataFetchingEnvironment) {
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
    }

    fun isComplete(env: DataFetchingEnvironment): Boolean {
        val totalOps = env.document.definitions.filterIsInstance<OperationDefinition>()
            .firstOrNull { it.operation == OperationDefinition.Operation.MUTATION }?.selectionSet?.selections?.size
        return totalOps == mutationFields.size
    }

    fun getChangeRequest(): ChangeRequest {
        return ChangeRequest(
            changeRequestId,
            request.context,
            request.podId,
            request.sliceId,
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

    private fun toJSON(objectValue: ObjectValue, type: GraphQLInputObjectType): JSONObject {
        val typeFqName = getFQName(type, request.context)
        return objectValue.objectFields.associate { field ->
            val fieldType = type.getField(field.name)
            val rawValue = field.value
            if (field.name == FIELD_ID_NAME) {
                JsonLdKeywords.id to (rawValue as StringValue).value.let {
                    JsonLdHelper.getFQName(it, request.context) ?: it
                }
            } else {
                getFQName(type.getField(field.name), request.context) to if (rawValue is ArrayValue) {
                    rawValue.values.map { listRawValue ->
                        if (listRawValue is ScalarValue<*>) convertScalar(listRawValue) else toJSON(
                            listRawValue as ObjectValue,
                            type.getFieldDefinition(field.name).type.innerType()
                        )
                    }
                } else {
                    if (rawValue is ScalarValue<*>) {
                        val scalarValue = convertScalar(rawValue)
                        if (fieldType.type == Scalars.GraphQLID) mapOf(JsonLdKeywords.id to scalarValue) else scalarValue
                    } else {
                        toJSON(
                            rawValue as ObjectValue,
                            type.getFieldDefinition(field.name).type.innerType()
                        )
                    }
                }
            }
        }.plus(JsonLdKeywords.type to typeFqName)
    }

    private fun contextualizeJson(value: JSONObject, type: GraphQLInputObjectType): JSONObject {
        val typeFqName = getFQName(type, request.context)
        return value.entries.associate { (fieldName, fieldValue) ->
            val fieldType = type.getField(fieldName)
            val rawValue = fieldValue
            if (fieldName == FIELD_ID_NAME) {
                JsonLdKeywords.id to (rawValue as String).let {
                    JsonLdHelper.getFQName(it, request.context) ?: it
                }
            } else {
                getFQName(type.getField(fieldName), request.context) to if (rawValue is Iterable<*>) {
                    rawValue.map { listRawValue ->
                        if (listRawValue is Map<*, *>) contextualizeJson(
                            listRawValue as JSONObject,
                            type.getFieldDefinition(fieldName).type.innerType()
                        ) else listRawValue
                    }
                } else {
                    if (rawValue is Map<*, *>) {
                        contextualizeJson(
                            rawValue as JSONObject,
                            type.getFieldDefinition(fieldName).type.innerType()
                        )
                    } else {
                        val scalarValue = rawValue
                        if (fieldType.type == Scalars.GraphQLID) mapOf(JsonLdKeywords.id to scalarValue) else scalarValue
                    }
                }
            }
        }.plus(JsonLdKeywords.type to typeFqName)
    }

    private fun convertScalar(value: ScalarValue<*>): Any {
        return when (value) {
            is BooleanValue -> value.isValue
            is FloatValue -> value.value
            is IntValue -> value.value
            is StringValue -> value.value
            else -> throw IllegalArgumentException("Unsupported scalar value type: ${value.javaClass}")
        }
    }

}