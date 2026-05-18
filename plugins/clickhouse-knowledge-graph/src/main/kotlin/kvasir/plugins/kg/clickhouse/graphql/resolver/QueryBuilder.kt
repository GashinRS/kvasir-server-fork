package kvasir.plugins.kg.clickhouse.graphql.resolver

import graphql.language.DirectivesContainer
import graphql.language.StringValue
import graphql.language.Value
import graphql.language.VariableReference
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition
import kvasir.definitions.kg.graphql.ARG_IF_NAME
import kvasir.definitions.kg.graphql.DIRECTIVE_FILTER_NAME
import kvasir.definitions.rdf.JSONObject
import kvasir.plugins.kg.clickhouse.graphql.resolver.nodeimpl.CompositeNode
import kvasir.utils.graphql.getDirectiveArg

class QueryBuilder(
    val context: JSONObject,
    val atChangeId: String?,
    val env: DataFetchingEnvironment,
    val mode: SQLConvertorMode = SQLConvertorMode.GET_DATA
) {

    val root = CompositeNode(
        context,
        atChangeId,
        env.field,
        env.fieldDefinition,
        "root",
        env
    )

    fun build(): String {
        val rootQuery = root.build(mode == SQLConvertorMode.GET_DATA)
        return when (mode) {
            SQLConvertorMode.GET_DATA -> rootQuery
            SQLConvertorMode.COUNT -> "SELECT count(*) as totalCount FROM ($rootQuery)"
        }
    }

}

enum class SQLConvertorMode {
    GET_DATA,
    COUNT
}

// Fetches filter arg value for a field (with fallback to fieldDefinition), taking into account potential variable references
internal fun getFilter(
    field: DirectivesContainer<*>,
    fieldDefinition: GraphQLFieldDefinition,
    env: DataFetchingEnvironment
): String? {
    return (field.getDirectiveArg<Value<*>>(DIRECTIVE_FILTER_NAME, ARG_IF_NAME)
        ?: fieldDefinition.getDirectiveArg<Value<*>>(DIRECTIVE_FILTER_NAME, ARG_IF_NAME))?.let { ifValue ->
        when (ifValue) {
            is StringValue -> ifValue.value
            is VariableReference -> {
                when (val value = env.variables[ifValue.name]) {
                    is String -> value
                    null -> throw IllegalArgumentException("Variable '${ifValue.name}' not found in the environment.")
                    else -> throw IllegalArgumentException("Unsupported variable type for 'if'-argument: ${value::class.simpleName}")
                }
            }

            else -> throw IllegalArgumentException("Unsupported 'if'-argument type: ${ifValue::class.simpleName}")
        }
    }
}

