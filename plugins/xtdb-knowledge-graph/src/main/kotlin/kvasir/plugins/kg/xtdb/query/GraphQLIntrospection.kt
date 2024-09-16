package kvasir.plugins.kg.xtdb.query

import com.google.common.base.CaseFormat
import graphql.language.*
import kvasir.definitions.rdf.RDFVocab

/**
 * Utility class that converts GraphQL introspection queries into XTDB SQL.
 */
class GraphQLIntrospection(private val parent: GraphQLToSQL) {

    companion object {

        const val SCHEMA_FIELD = "__schema"
        const val SCHEMA_QUERY_TYPE_FIELD = "queryType"
        const val SCHEMA_TYPES_FIELD = "types"
        const val SCHEMA_MUTATION_TYPE_FIELD = "mutationType"
        const val SCHEMA_SUBSCRIPTION_TYPE_FIELD = "subscriptionType"
        const val SCHEMA_DIRECTIVES_FIELD = "directives"

        const val TYPE_FIELD = "__type"
        const val TYPE_NAME_FIELD = "name"
        const val TYPE_KIND_FIELD = "kind"
        const val TYPE_FIELDS_FIELD = "fields"
        const val TYPE_DESCRIPTION_FIELD = "description"
        const val TYPE_INPUT_FIELDS_FIELD = "inputFields"
        const val TYPE_ENUM_VALUES_FIELD = "enumValues"
        const val TYPE_INTERFACES_FIELD = "interfaces"
        const val TYPE_POSSIBLE_TYPES_FIELD = "possibleTypes"

        const val FIELD_NAME_FIELD = "name"
        const val FIELD_DESCRIPTION_FIELD = "description"
        const val FIELD_ARGS_FIELD = "args"
        const val FIELD_TYPE_FIELD = "type"
        const val FIELD_IS_DEPRECATED_FIELD = "isDeprecated"
        const val FIELD_DEPRECATION_REASON_FIELD = "deprecationReason"

        const val QUERY_TYPE_NAME_FIELD = "name"

    }

    private fun initFieldMapping() {
        listOf(
            SCHEMA_QUERY_TYPE_FIELD,
            SCHEMA_MUTATION_TYPE_FIELD,
            SCHEMA_SUBSCRIPTION_TYPE_FIELD,
            TYPE_INPUT_FIELDS_FIELD,
            TYPE_ENUM_VALUES_FIELD,
            TYPE_POSSIBLE_TYPES_FIELD,
            FIELD_IS_DEPRECATED_FIELD,
            FIELD_DEPRECATION_REASON_FIELD
        ).forEach {
            val snakeCaseKey: String = CaseFormat.LOWER_CAMEL.converterTo(CaseFormat.LOWER_UNDERSCORE).convert(it)!!
            parent.fieldMapping[snakeCaseKey] = it
        }
    }

    fun schema(selection: Field): String {
        initFieldMapping()
        return selection.selectionSet?.selections?.filterIsInstance<Field>()?.takeIf { it.isNotEmpty() }?.joinToString(
            ", ",
            "NEST_ONE(SELECT DISTINCT ",
            ") AS ${selection.alias ?: selection.name}"
        ) { schemaField ->
            when (schemaField.name) {
                SCHEMA_QUERY_TYPE_FIELD -> queryType(schemaField)
                SCHEMA_TYPES_FIELD -> types(schemaField)
                // Unused fields
                SCHEMA_MUTATION_TYPE_FIELD, SCHEMA_SUBSCRIPTION_TYPE_FIELD -> "NULL AS ${schemaField.name}"
                SCHEMA_DIRECTIVES_FIELD -> "[] AS ${schemaField.name}"
                // Unsupported fields
                else -> throw IllegalArgumentException("Unsupported introspection field on __schema: ${schemaField.name}")
            }
        } ?: throw IllegalArgumentException("No fields specified on __schema")
    }

    fun type(selection: Field): String {
        initFieldMapping()
        val requestedTypeFQId = selection.arguments.find { it.name == "name" }?.let {
            (it.value as StringValue).value
        } ?: throw IllegalArgumentException("Missing 'name' argument on __type field")
        return selection.selectionSet?.selections?.filterIsInstance<Field>()?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ", "NEST_ONE(SELECT DISTINCT", ") AS ${selection.alias ?: selection.name}") {
                when (it.name) {
                    TYPE_NAME_FIELD -> "'$requestedTypeFQId' AS name"
                    TYPE_FIELDS_FIELD -> {
                        val fieldsSubFields = it.selectionSet?.selections?.filterIsInstance<Field>() ?: emptyList()
                        if (fieldsSubFields.size == 1 && fieldsSubFields.first().name == "name") {
                            "NEST_MANY(SELECT DISTINCT p AS name  FROM ${parent.database} WHERE NOT p = '${RDFVocab.type}' AND s IN (SELECT DISTINCT s FROM ${parent.database} WHERE p = '${RDFVocab.type}' AND o = '$requestedTypeFQId') ORDER BY name) AS fields"
                        } else {
                            throw IllegalArgumentException("Currently only 'name' field is supported on 'fields' field of __type")
                        }
                    }

                    else -> throw IllegalArgumentException("Unsupported introspection field on __type: ${it.name}")
                }
            } ?: throw IllegalArgumentException("No fields specified on __type")
    }

    fun types(selection: Field): String {
        val selectedFields =
            selection.selectionSet?.selections?.flatMap { resolveFragment(it) }?.filterIsInstance<Field>()
        return selectedFields
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(
                ", ",
                "NEST_MANY(SELECT DISTINCT ",
                " FROM ${parent.database} t WHERE t.p = '${RDFVocab.type}' ORDER BY t.o) AS types"
            ) { typesField ->
                when (typesField.name) {
                    TYPE_NAME_FIELD -> "t.o AS name"
                    TYPE_KIND_FIELD -> "'OBJECT' AS kind"
                    TYPE_FIELDS_FIELD -> fields(typesField)
                    // Unused fields
                    TYPE_DESCRIPTION_FIELD, TYPE_INPUT_FIELDS_FIELD, TYPE_ENUM_VALUES_FIELD, TYPE_POSSIBLE_TYPES_FIELD -> "NULL AS ${typesField.name}"
                    TYPE_INTERFACES_FIELD -> "[] AS ${typesField.name}"
                    // Unsupported fields
                    else -> throw IllegalArgumentException("Currently only 'name' and 'fields' fields are supported on 'types' field of __schema")
                }
            } ?: throw IllegalArgumentException("No fields specified on 'types' field of __schema")
    }

    fun fields(selection: Field): String {
        val fieldsSubFields =
            selection.selectionSet?.selections?.filterIsInstance<Field>()
        return fieldsSubFields
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(
                ", ",
                "NEST_MANY(SELECT DISTINCT ",
                " FROM ${parent.database} f WHERE NOT f.p = '${RDFVocab.type}' AND f.s IN (SELECT s FROM ${parent.database} WHERE p = '${RDFVocab.type}' AND o = t.o)) AS fields"
            ) { fieldsField ->
                when (fieldsField.name) {
                    FIELD_NAME_FIELD -> "f.p AS name"
                    FIELD_TYPE_FIELD -> "CASE WHEN f.t[1] = 'Literal' THEN t[2] ELSE (SELECT DISTINCT ARRAY_AGG(o) FROM ${parent.database} WHERE p = '${RDFVocab.type}' AND s IN (SELECT o FROM ${parent.database} WHERE p = f.p) GROUP BY p) END AS type"
                    FIELD_DESCRIPTION_FIELD -> "NULL AS description"
                    FIELD_ARGS_FIELD -> "[] AS args"
                    // Unused fields
                    FIELD_IS_DEPRECATED_FIELD -> "false AS isDeprecated"
                    FIELD_DEPRECATION_REASON_FIELD -> "NULL AS ${fieldsField.name}"
                    // Unsupported fields
                    else -> throw IllegalArgumentException("Unsupported introspection field on __field: ${fieldsField.name}")
                }
            } ?: throw IllegalArgumentException("No fields specified on 'fields'")
    }

    fun queryType(selection: Field): String {
        return selection.selectionSet?.selections?.filterIsInstance<Field>()?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ", "NEST_ONE(SELECT DISTINCT ", ") AS queryType") { queryTypeField ->
                when (queryTypeField.name) {
                    QUERY_TYPE_NAME_FIELD -> "'Query' AS name"
                    else -> throw IllegalArgumentException("Currently only 'name' field is supported on 'queryType' field of __schema")
                }
            } ?: throw IllegalArgumentException("No fields specified on 'queryType' field of __schema")
    }

    private fun resolveFragment(selection: Selection<*>): List<Selection<*>> {
        return when (selection) {
//            is FragmentSpread -> {
//                parent.request.query.getDefinitionsOfType(FragmentDefinition::class.java)
//                    .find { it.name == selection.name }?.selectionSet?.selections
//                    ?: throw IllegalArgumentException("Cannot find referenced fragment: ${selection.name}")
//            }

            else -> listOf(selection)
        }
    }

}