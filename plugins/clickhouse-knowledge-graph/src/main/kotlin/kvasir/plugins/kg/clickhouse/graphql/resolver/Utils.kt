package kvasir.plugins.kg.clickhouse.graphql.resolver

import graphql.language.BooleanValue
import graphql.language.Field
import graphql.schema.*
import kvasir.definitions.kg.graphql.*
import kvasir.definitions.persistence.SortOrder
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.plugins.kg.clickhouse.graphql.resolver.nodeimpl.CompositeNode
import kvasir.plugins.kg.clickhouse.graphql.resolver.nodeimpl.ScalarCollectionNode
import kvasir.plugins.kg.clickhouse.graphql.resolver.nodeimpl.ScalarValueNode
import kvasir.utils.graphql.*

internal const val SUBJECT_MATCH = "sub_match"
internal const val COUNT = "_count"

internal fun getAvailableFieldDefinitions(type: GraphQLType): List<GraphQLFieldDefinition> {
    return when (type) {
        is GraphQLObjectType -> type.fieldDefinitions
        is GraphQLInterfaceType -> type.fieldDefinitions
        else -> emptyList()
    }
}

data class RelationInfo(
    val field: Field,
    val fieldDefinition: GraphQLFieldDefinition,
    val parentType: GraphQLCompositeType,
    val context: JSONObject
) {
    // TODO: make reverse work when defined in context vs. in the graphql schema
    val reverse = fieldDefinition.getDirectiveArg<BooleanValue>(
        DIRECTIVE_PREDICATE_NAME,
        ARG_REVERSE_NAME
    )?.isValue ?: false
    val separator = if (reverse) "<-" else "->"
    val identifier = "`${parentType.name}$separator${getVariableNameForField(fieldDefinition, context)}`"
}

data class TypeInfo(
    val type: GraphQLCompositeType,
    val fieldDefinitions: Set<GraphQLFieldDefinition>
) {
    val identifier = type.name
}


data class FieldInfo(
    val type: GraphQLCompositeType,
    val field: Field,
    val fieldDefinition: GraphQLFieldDefinition,
    val inFragment: Boolean = false
) {
    companion object {
        fun from(
            type: GraphQLCompositeType,
            field: Field,
            availableFieldDefinitions: List<GraphQLFieldDefinition>,
            inFragment: Boolean = false
        ): FieldInfo {
            val fieldDefinition = availableFieldDefinitions.find { it.name == field.name }
                ?: throw RuntimeException("Unexpected error: could not find definition for nested field '${field.name}'")
            return FieldInfo(type, field, fieldDefinition, inFragment)
        }
    }
}

fun getVariableNameForField(
    fieldDefinition: GraphQLFieldDefinition,
    context: JSONObject,
    fallbackValue: String? = null
): String {
    return try {
        val fqFieldName = getFQName(fieldDefinition, context)
        JsonLdHelper.getUniqueVariableNameInContext(fqFieldName, context)
    } catch (e: IllegalArgumentException) {
        if (KVASIR_BUILT_IN_FIELDS.contains(fieldDefinition.name)) {
            fieldDefinition.name
        } else fallbackValue ?: throw e
    }
}

data class SortKey(val fieldName: String, val direction: SortOrder) {
    companion object {
        fun parseFieldSortOrder(field: Field, env: DataFetchingEnvironment): List<SortKey>? {
            return field.getStringArrayArgument(ARG_ORDER_BY_NAME, env.variables)?.takeIf { it.isNotEmpty() }
                ?.let { fields ->
                    fields.map { fieldExpr ->
                        val direction = if (fieldExpr.startsWith("-")) SortOrder.DESC else SortOrder.ASC
                        val fieldName = fieldExpr.removePrefix("-")
                        SortKey(fieldName, direction)
                    }
                }
        }

        fun toSQL(sortKeys: List<SortKey>): String {
            return sortKeys.let { sortKeys -> " ORDER BY " + sortKeys.joinToString { (fieldName, order) -> "$fieldName $order" } }
        }

        fun toArraySortSQL(sortKeys: List<SortKey>, arrayExpr: String): String {
            if (sortKeys.isEmpty()) return arrayExpr

            val allAsc = sortKeys.all { it.direction == SortOrder.ASC }
            val allDesc = sortKeys.all { it.direction == SortOrder.DESC }

            return when {
                // Optimization: All fields are Ascending
                allAsc -> "arraySort(x -> ${renderTuple(sortKeys)}, $arrayExpr)"

                // Optimization: All fields are Descending
                allDesc -> "arrayReverseSort(x -> ${renderTuple(sortKeys)}, $arrayExpr)"

                // Mixed directions: Use the byte-inversion trick for DESC strings
                else -> "arraySort(x -> ${renderMixedTuple(sortKeys)}, $arrayExpr)"
            }
        }

        private fun renderTuple(keys: List<SortKey>): String {
            return if (keys.size == 1) "x['${keys[0].fieldName}']"
            else keys.joinToString(prefix = "(", postfix = ")") { "x['${it.fieldName}']" }
        }

        private fun renderMixedTuple(keys: List<SortKey>): String {
            val expressions = keys.map { key ->
                val fieldAccess = "x['${key.fieldName}']"
                if (key.direction == SortOrder.ASC) {
                    fieldAccess
                } else {
                    // Descending string transformation
                    "arrayMap(b -> 255 - b, cast($fieldAccess, 'Array(UInt8)'))"
                }
            }
            return if (expressions.size == 1) expressions[0]
            else expressions.joinToString(prefix = "(", postfix = ")")
        }
    }
}

fun mapFieldToQueryTreeNode(
    field: Field,
    fieldDefinition: GraphQLFieldDefinition,
    parent: CompositeNode,
    overrideJoinType: String? = null
): QueryTreeNode {
    val isList = fieldDefinition.type.isList()
    val isScalar = fieldDefinition.type.isScalar()
    // TODO: make reverse work when defined in context vs. in the graphql schema
    val reverse = fieldDefinition.getDirectiveArg<BooleanValue>(
        DIRECTIVE_PREDICATE_NAME,
        ARG_REVERSE_NAME
    )?.isValue ?: false
    return when {
        !isScalar -> CompositeNode(
            parent.context,
            field,
            fieldDefinition,
            "${field.alias ?: field.name}_scope",
            parent.env,
            parent,
            overrideJoinType = overrideJoinType
        )

        isList || reverse -> ScalarCollectionNode(
            field,
            fieldDefinition,
            parent,
            parent.context,
            overrideJoinType = overrideJoinType
        )

        else -> ScalarValueNode(field, fieldDefinition, parent)
    }
}

fun isOptional(field: Field, fieldDefinition: GraphQLFieldDefinition): Boolean {
    if (field.hasDirective(DIRECTIVE_OPTIONAL_NAME)) {
        if (isMustExist(fieldDefinition)) {
            throw IllegalArgumentException("Cannot use @optional on field '${field.name}' because its schema definition is marked with @mustExist!")
        }
        return true
    }
    return false
}

fun isMustExist(fieldDefinition: GraphQLFieldDefinition): Boolean {
    return fieldDefinition.hasAppliedDirective(DIRECTIVE_MUST_EXIST_NAME)
}