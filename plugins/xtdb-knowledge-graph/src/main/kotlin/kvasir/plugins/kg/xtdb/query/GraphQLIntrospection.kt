package kvasir.plugins.kg.xtdb.query

import graphql.language.*
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.rdf.RDFVocab
import kvasir.plugins.kg.xtdb.dbNameForPod

/**
 * Utility class that converts GraphQL introspection queries into XTDB SQL.
 */
class GraphQLIntrospection(private val request: QueryRequest) {

    private val database = dbNameForPod(request.podId)

    fun schema(selection: Field): String {
        return selection.selectionSet?.selections?.filterIsInstance<Field>()?.takeIf { it.isNotEmpty() }?.joinToString(
            ", ",
            "NEST_ONE(SELECT DISTINCT ",
            ") AS ${selection.alias ?: selection.name}"
        ) { schemaField ->
            when (schemaField.name) {
                "queryType" -> queryType(schemaField)
                "types" -> types(schemaField)
                // Unused fields
                "mutationType", "subscriptionType" -> "NULL AS ${schemaField.name}"
                "directives" -> "[] AS ${schemaField.name}"
                // Unsupported fields
                else -> throw IllegalArgumentException("Unsupported introspection field on __schema: ${schemaField.name}")
            }
        } ?: throw IllegalArgumentException("No fields specified on __schema")
    }

    fun type(selection: Field): String {
        val requestedTypeFQId = selection.arguments.find { it.name == "name" }?.let {
            (it.value as StringValue).value
        } ?: throw IllegalArgumentException("Missing 'name' argument on __type field")
        return selection.selectionSet?.selections?.filterIsInstance<Field>()?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ", "NEST_ONE(SELECT DISTINCT", ") AS ${selection.alias ?: selection.name}") {
                when (it.name) {
                    "name" -> "'$requestedTypeFQId' AS name"
                    "fields" -> {
                        val fieldsSubFields = it.selectionSet?.selections?.filterIsInstance<Field>() ?: emptyList()
                        if (fieldsSubFields.size == 1 && fieldsSubFields.first().name == "name") {
                            "NEST_MANY(SELECT DISTINCT p AS name  FROM $database WHERE NOT p = '${RDFVocab.type}' AND s IN (SELECT DISTINCT s FROM $database WHERE p = '${RDFVocab.type}' AND o = '$requestedTypeFQId') ORDER BY name) AS fields"
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
                " FROM $database t WHERE t.p = '${RDFVocab.type}' ORDER BY t.o) AS types"
            ) { typesField ->
                when (typesField.name) {
                    "name" -> "t.o AS name"
                    "kind" -> "'OBJECT' AS kind"
                    "fields" -> fields(typesField)
                    // Unused fields
                    "description", "inputFields", "enumValues", "possibleTypes" -> "NULL AS ${typesField.name}"
                    "interfaces" -> "[] AS ${typesField.name}"
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
                " FROM $database WHERE NOT p = '${RDFVocab.type}' AND s IN (SELECT s FROM $database WHERE p = '${RDFVocab.type}' AND o = t.o)) AS fields"
            ) { fieldsField ->
                when (fieldsField.name) {
                    "name" -> "p AS name"
                    "type" -> "{ kind: 'SCALAR', name: 'String', ofType: null } AS type"
                    "description" -> "NULL AS description"
                    "args" -> "[] AS args"
                    // Unused fields
                    "isDeprecated" -> "false AS isDeprecated"
                    "deprecationReason" -> "NULL AS ${fieldsField.name}"
                    // Unsupported fields
                    else -> throw IllegalArgumentException("Unsupported introspection field on __field: ${fieldsField.name}")
                }
            } ?: throw IllegalArgumentException("No fields specified on 'fields'")
    }

    fun queryType(selection: Field): String {
        return selection.selectionSet?.selections?.filterIsInstance<Field>()?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ", "NEST_ONE(SELECT DISTINCT ", ")") { queryTypeField ->
                when (queryTypeField.name) {
                    "name" -> "Query AS name"
                    else -> throw IllegalArgumentException("Currently only 'name' field is supported on 'queryType' field of __schema")
                }
            } ?: throw IllegalArgumentException("No fields specified on 'queryType' field of __schema")
    }

    private fun resolveFragment(selection: Selection<*>): List<Selection<*>> {
        return when (selection) {
            is FragmentSpread -> {
                request.graphQL.getDefinitionsOfType(FragmentDefinition::class.java)
                    .find { it.name == selection.name }?.selectionSet?.selections
                    ?: throw IllegalArgumentException("Cannot find referenced fragment: ${selection.name}")
            }

            else -> listOf(selection)
        }
    }

}