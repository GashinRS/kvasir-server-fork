package kvasir.baseimpl.kg

import graphql.language.*
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLObjectType
import kvasir.definitions.kg.ChangeRequest
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.kg.graphql.FIELD_ID_NAME
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
        val instances = env.field.arguments.zip(env.fieldDefinition.arguments) { argument, argumentDefinition ->
            when (val argValue = argument.value) {
                is ObjectValue -> listOf(toJSON(argValue, argumentDefinition.type.innerType()))
                is ArrayValue -> argValue.values.map { toJSON(it as ObjectValue, argumentDefinition.type.innerType()) }
                else -> null
            }
        }.filterNotNull().flatten()
        if (env.field.name.startsWith("add") || env.field.name.startsWith("insert")) {
            inserts.addAll(instances)
        }
        if (env.field.name.startsWith("remove") || env.field.name.startsWith("delete")) {
            deletes.addAll(instances)
        }
    }

    fun isComplete(env: DataFetchingEnvironment): Boolean {
        return (env.parentType as GraphQLObjectType).fields.size == mutationFields.size
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

    private fun toJSON(objectValue: ObjectValue, type: GraphQLInputObjectType): Map<String, Any> {
        val typeFqName = getFQName(type, request.context)
        return objectValue.objectFields.associate { field ->
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
                    if (rawValue is ScalarValue<*>) convertScalar(rawValue) else toJSON(
                        rawValue as ObjectValue,
                        type.getFieldDefinition(field.name).type.innerType()
                    )
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