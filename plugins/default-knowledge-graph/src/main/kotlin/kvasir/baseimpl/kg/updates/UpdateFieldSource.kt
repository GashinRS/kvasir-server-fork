package kvasir.baseimpl.kg

import graphql.language.*
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLScalarType
import kvasir.definitions.kg.graphql.*
import kvasir.utils.graphql.MutationInputConverter
import kvasir.utils.graphql.innerType

// ── UpdateFieldEntry ──────────────────────────────────────────────────────────

/**
 * Abstracts a single field value in an update input, hiding whether the mutation was
 * sent as an inline AST literal ([AstFieldEntry]) or a deserialized JSON variable ([JsonFieldEntry]).
 *
 * Both paths implement:
 * - [toInsertValue] — convert the value to JSON-LD for plain replace operations.
 * - [extractUpdatableOp] — decode the operation from a _Updatable* input object.
 */
internal sealed interface UpdateFieldEntry {

    /**
     * Converts this field entry to an insert value suitable for a JSON-LD insert payload,
     * or returns `null` when the field represents a deletion (`null` / [NullValue]).
     */
    fun toInsertValue(fieldDefinition: GraphQLInputObjectField, converter: MutationInputConverter): Any?

    /**
     * Decodes the [UpdatableOp] encoded in this entry, assuming the field type is a _Updatable* input type.
     */
    fun extractUpdatableOp(): UpdatableOp
}

/** Field entry backed by a GraphQL AST [Value] node (inline literal in the mutation document). */
internal data class AstFieldEntry(val value: Value<*>) : UpdateFieldEntry {

    override fun toInsertValue(fieldDefinition: GraphQLInputObjectField, converter: MutationInputConverter): Any? {
        if (value is NullValue) return null
        return if (value is ArrayValue) {
            value.values.map { converter.singleValueToJSON(it, fieldDefinition) }
        } else {
            converter.singleValueToJSON(value, fieldDefinition)
        }
    }

    override fun extractUpdatableOp(): UpdatableOp {
        if (value is NullValue) return UpdatableOp.Set(null)
        val obj = value as? ObjectValue
            ?: throw IllegalArgumentException("Expected ObjectValue for _Updatable* field, got ${value::class.simpleName}")
        val opField = obj.objectFields.firstOrNull { it.value !is NullValue }
            ?: return UpdatableOp.Set(null)  // all fields null → treat as full delete
        return when (opField.name) {
            UPDATABLE_FIELD_SET -> UpdatableOp.Set(opField.value)
            UPDATABLE_FIELD_INCREMENT, UPDATABLE_FIELD_DECREMENT, UPDATABLE_FIELD_MULTIPLY -> {
                val numStr = when (val v = opField.value) {
                    is IntValue -> v.value.toString()
                    is FloatValue -> v.value.toPlainString()
                    else -> throw IllegalArgumentException(
                        "Expected numeric value for ${opField.name}, got ${v::class.simpleName}"
                    )
                }
                UpdatableOp.Arithmetic(opField.name, numStr)
            }
            UPDATABLE_FIELD_APPEND, UPDATABLE_FIELD_PREPEND, UPDATABLE_FIELD_TEMPLATE -> {
                val str = (opField.value as? StringValue)?.value
                    ?: throw IllegalArgumentException("Expected string value for ${opField.name}")
                UpdatableOp.StringOp(opField.name, str)
            }
            UPDATABLE_FIELD_ADD -> {
                val values = (opField.value as? ArrayValue)?.values?.filterIsInstance<Value<*>>()
                    ?: throw IllegalArgumentException("Expected array value for ${opField.name}")
                UpdatableOp.CollectionAdd(values)
            }
            UPDATABLE_FIELD_REMOVE -> {
                val values = (opField.value as? ArrayValue)?.values?.filterIsInstance<Value<*>>()
                    ?: throw IllegalArgumentException("Expected array value for ${opField.name}")
                UpdatableOp.CollectionRemove(values)
            }
            else -> throw IllegalArgumentException("Unknown _Updatable operation field: '${opField.name}'")
        }
    }
}

/** Field entry backed by a deserialized JSON value from a GraphQL variable map. */
internal data class JsonFieldEntry(val value: Any?) : UpdateFieldEntry {

    override fun toInsertValue(fieldDefinition: GraphQLInputObjectField, converter: MutationInputConverter): Any? {
        if (value == null) return null
        val scalarType = fieldDefinition.type.innerType<GraphQLScalarType>()
        return if (value is Iterable<*>) {
            value.map { converter.convertJSONScalar(it!!, scalarType) }
        } else {
            converter.convertJSONScalar(value, scalarType)
        }
    }

    override fun extractUpdatableOp(): UpdatableOp {
        if (value == null) return UpdatableOp.Set(null)
        val map = value as? Map<*, *>
            ?: throw IllegalArgumentException("Expected Map for _Updatable* field")
        val opEntry = map.entries.firstOrNull { it.value != null }
            ?: return UpdatableOp.Set(null)  // all fields null → treat as full delete
        val opName = opEntry.key as String
        val opValue = opEntry.value!!
        return when (opName) {
            UPDATABLE_FIELD_SET -> UpdatableOp.Set(opValue)
            UPDATABLE_FIELD_INCREMENT, UPDATABLE_FIELD_DECREMENT, UPDATABLE_FIELD_MULTIPLY ->
                UpdatableOp.Arithmetic(opName, opValue.toString())
            UPDATABLE_FIELD_APPEND, UPDATABLE_FIELD_PREPEND, UPDATABLE_FIELD_TEMPLATE ->
                UpdatableOp.StringOp(opName, opValue as String)
            UPDATABLE_FIELD_ADD -> UpdatableOp.CollectionAdd((opValue as Iterable<*>).filterNotNull())
            UPDATABLE_FIELD_REMOVE -> UpdatableOp.CollectionRemove((opValue as Iterable<*>).filterNotNull())
            else -> throw IllegalArgumentException("Unknown _Updatable operation field: '$opName'")
        }
    }
}

// ── UpdateFieldSource ─────────────────────────────────────────────────────────

/**
 * Abstracts over the two input representations — GraphQL AST ([AstUpdateFieldSource]) and
 * deserialized variable maps ([MapUpdateFieldSource]) — so that [UpdateMutationCompiler]
 * processes both through a single code path.
 */
internal interface UpdateFieldSource {
    /** Extracts the required `id` field as a plain string. */
    fun extractId(): String

    /** Iterates over every non-`id` field, invoking [action] with its name, schema definition, and entry. */
    fun forEachField(
        type: GraphQLInputObjectType,
        action: (schemaFieldName: String, fieldDefinition: GraphQLInputObjectField, entry: UpdateFieldEntry) -> Unit
    )
}

/** [UpdateFieldSource] backed by a GraphQL AST [ObjectValue] (inline literal). */
internal class AstUpdateFieldSource(
    private val env: DataFetchingEnvironment,
    private val objectValue: ObjectValue
) : UpdateFieldSource {

    override fun extractId(): String {
        val idField = objectValue.objectFields.find { it.name == FIELD_ID_NAME }
            ?: throw IllegalArgumentException("Update mutation requires an 'id' field")
        return when (val v = idField.value) {
            is StringValue -> v.value
            is VariableReference -> env.variables[v.name]?.toString()
                ?: throw IllegalArgumentException("Variable '${v.name}' not found")
            else -> throw IllegalArgumentException("Unsupported id value type: ${v::class.simpleName}")
        }
    }

    override fun forEachField(
        type: GraphQLInputObjectType,
        action: (String, GraphQLInputObjectField, UpdateFieldEntry) -> Unit
    ) {
        for (field in objectValue.objectFields) {
            if (field.name == FIELD_ID_NAME) continue
            action(field.name, type.getField(field.name), AstFieldEntry(field.value))
        }
    }
}

/** [UpdateFieldSource] backed by a deserialized variable [Map]. */
internal class MapUpdateFieldSource(private val map: Map<String, Any?>) : UpdateFieldSource {

    override fun extractId(): String {
        return map[FIELD_ID_NAME]?.toString()
            ?: throw IllegalArgumentException("Update mutation requires an 'id' field")
    }

    override fun forEachField(
        type: GraphQLInputObjectType,
        action: (String, GraphQLInputObjectField, UpdateFieldEntry) -> Unit
    ) {
        for ((fieldName, fieldValue) in map) {
            if (fieldName == FIELD_ID_NAME) continue
            action(fieldName, type.getField(fieldName), JsonFieldEntry(fieldValue))
        }
    }
}

