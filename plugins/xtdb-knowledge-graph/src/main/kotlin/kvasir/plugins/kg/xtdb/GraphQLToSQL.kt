package kvasir.plugins.kg.xtdb

import com.google.common.hash.Hashing
import graphql.language.*
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.rdf.RDFVocab

class GraphQLToSQL(private val request: QueryRequest) {

    private val database = Hashing.farmHashFingerprint64().hashString(request.podId, Charsets.UTF_8).toString()

    fun toSQL(): String {
        // Verify that only a single query is specified and assign it (otherwise throw an exception)
        val rootNode = request.graphQL.definitions.filterIsInstance<OperationDefinition>()
            .firstOrNull { it.operation == OperationDefinition.Operation.QUERY }
            ?: throw IllegalArgumentException("Only one query is allowed")
        return mapNode(
            rootNode,
            "SELECT DISTINCT s FROM $database"
        ) // TODO take into account field arguments as conditions
    }

    private fun mapNode(
        node: AbstractNode<*>,
        subjectSelection: String,
        id: String = ""
    ): String {
        val dataAlias = "${id}data"
        val selectionSetContainer = node as SelectionSetContainer<*>
        val fieldJoinClauses = mutableListOf<String>()
        val nestedNonNullFields = mutableListOf<NestedNonNullFieldCheck>()
        val dataSelectClauses = selectionSetContainer.selectionSet.selections.joinToString(", ") { selection ->
            mapSelection(selection, id, nestedNonNullFields, dataAlias, fieldJoinClauses)
        }
        val dataSelection =
            "SELECT $dataSelectClauses FROM ($subjectSelection) $dataAlias ${fieldJoinClauses.joinToString(" ")}"
        return nestedNonNullFields.takeIf { it.isNotEmpty() }
            ?.let { fieldsToCheck ->
                "SELECT * FROM ($dataSelection) ${id}env WHERE ${fieldsToCheck.joinToString(" AND ") { "`${it.name}`${if (it.hasMultipleResults) "[1]" else ""} IS NOT null" }}"
            }
            ?: dataSelection
    }

    private fun mapSelection(
        selection: Selection<*>?,
        scopeId: String,
        nestedNonNullFields: MutableList<NestedNonNullFieldCheck>,
        dataAlias: String,
        fieldJoinClauses: MutableList<String>,
        fqTypeBound: String? = null
    ): String {
        when (selection) {
            is Field -> {
                val isOptional = selection.directives.any { it.name == "optional" }
                val effectiveName = (selection.alias ?: selection.name)
                if (selection.name == "id") {
                    return "$dataAlias.s AS `$effectiveName`" // TODO: cleaner way of shortcutting logic when field is 'id'
                }

                val valueFilter = selection.arguments.firstOrNull { it.name == "_" }?.value?.let {
                    when (it) {
                        is FloatValue -> " AND o = ${it.value}"
                        is IntValue -> " AND o = ${it.value}"
                        is BooleanValue -> " AND o = ${it.isValue}"
                        is StringValue -> " AND o = '${it.value}'"
                        else -> throw IllegalArgumentException("Unsupported filter value type: $it")
                    }
                }.orEmpty()
                val joinName = "${scopeId}$effectiveName"
                val hasMultipleResults = !selection.hasDirective("single")
                return if (selection.selectionSet != null) {
                    // Field has a nested selection set, recurse

                    // Workaround to make sure that null results are filtered out when the field is not optional.
                    if (!isOptional) {
                        nestedNonNullFields.add(NestedNonNullFieldCheck(effectiveName, hasMultipleResults))
                    }

                    // Calculate additional type condition (if any)
                    val typeCondition = fqTypeBound?.let { getTypeWhereCondition(it, "o") }.orEmpty()

                    // Specify a subjectSelection based on the objects matching the specified subject & predicate)
                    "${if (hasMultipleResults) "NEST_MANY" else "NEST_ONE"}(${
                        mapNode(
                            selection,
                            "SELECT DISTINCT o AS s FROM $database WHERE s = ${dataAlias}.s AND p = '${selection.getContextIRI()}'$valueFilter$typeCondition${if (!hasMultipleResults) " LIMIT 1" else ""}",
                            effectiveName.plus("_")
                        )
                    }) AS `$effectiveName`"
                } else if (selection.name == "__fieldnames") {
                    // Special introspection field to return the field names of the current selection
                    fieldJoinClauses.add("JOIN (SELECT s, '__fieldnames' as p, ARRAY_AGG(p) as o FROM $database) $joinName ON $joinName.s = $dataAlias.s")
                    "$joinName.o AS `$effectiveName`"
                } else {
                    // Field is a leaf node: use join to select objects matching the specified subject & predicate
                    val typeCondition = fqTypeBound?.let { getTypeWhereCondition(it, "s") }.orEmpty()
                    val fieldJoinClause =
                        "JOIN (SELECT s, ARRAY_AGG(o) as o FROM $database WHERE p = '${selection.getContextIRI()}'$valueFilter$typeCondition) $joinName ON $joinName.s = $dataAlias.s"
                    fieldJoinClauses.add(if (isOptional) "LEFT ".plus(fieldJoinClause) else fieldJoinClause)
                    "$joinName.o${if (!hasMultipleResults) "[1]" else ""} AS `$effectiveName`"
                }
            }

            is InlineFragment -> {
                // Inline fragment, treat included selection set as fields, but with an additional type condition
                val fqType = selection.getContextIRI()
                return selection.selectionSet.selections.joinToString(", ") { inlineSelection ->
                    mapSelection(inlineSelection, scopeId, nestedNonNullFields, dataAlias, fieldJoinClauses, fqType)
                }
            }

            is FragmentSpread -> {
                // Fragment spread, lookup FragmentDefinition...
                val fragmentDefinition = request.graphQL.getDefinitionsOfType(FragmentDefinition::class.java)
                    .firstOrNull { it.name == selection.name }
                    ?: throw IllegalArgumentException("Fragment definition for '${selection.name}' not found")

                //... and treat included selection set as fields, but with an additional type condition )
                val fqType = fragmentDefinition.getContextIRI()
                return fragmentDefinition.selectionSet.selections.joinToString(", ") { inlineSelection ->
                    mapSelection(inlineSelection, scopeId, nestedNonNullFields, dataAlias, fieldJoinClauses, fqType)
                }
            }

            else -> throw IllegalArgumentException("Unsupported selection type: $selection")
        }
    }

    private fun getTypeWhereCondition(fqTypeBound: String, targetColumn: String): String {
        return "AND $targetColumn IN (SELECT s FROM $database WHERE p = '${RDFVocab.type}' AND o = '$fqTypeBound')"
    }
}

private fun <T : DirectivesContainer<T>> DirectivesContainer<T>.getContextIRI(): String {
    return getDirectives("context").firstOrNull()?.getArgument("iri")?.value?.let { (it as StringValue).value }
        ?: throw IllegalArgumentException("Missing semantic context for ${this::class.simpleName} at ${sourceLocation}!")
}

data class NestedNonNullFieldCheck(val name: String, val hasMultipleResults: Boolean)