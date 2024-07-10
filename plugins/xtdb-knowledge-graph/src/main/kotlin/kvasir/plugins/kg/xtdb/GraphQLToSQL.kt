package kvasir.plugins.kg.xtdb

import com.google.common.hash.Hashing
import graphql.language.*
import graphql.parser.Parser
import kvasir.definitions.kg.QueryRequest

class GraphQLToSQL(private val request: QueryRequest) {

    private val database = Hashing.farmHashFingerprint64().hashString(request.podId, Charsets.UTF_8).toString()

    fun toSQL(): String {
        // Verify that only a single query is specified and assign it (otherwise throw an exception)
        val rootNode = request.graphQL.definitions.filterIsInstance<OperationDefinition>()
            .firstOrNull { it.operation == OperationDefinition.Operation.QUERY }
            ?: throw IllegalArgumentException("Only one query is allowed")
        return mapNode(rootNode)
    }


    // TODO: clean up & modularize
    fun mapNode(
        node: AbstractNode<*>,
        joinId: String? = null,
        id: String = ""
    ): String {
        val dataAlias = "${id}data"
        val selectionSetContainer = node as SelectionSetContainer<*>
        val subjectWereClause = joinId?.let { " WHERE s = $joinId.o" } ?: ""
        val subjectSelection = "SELECT DISTINCT s FROM $database$subjectWereClause"
        val fieldJoinClauses = mutableListOf<String>()
        val nestedNonNullFields = mutableListOf<String>()
        val dataSelectClauses = selectionSetContainer.selectionSet.selections.joinToString(", ") { selection ->
            when (selection) {
                is Field -> {
                    val isOptional = selection.directives.any { it.name == "optional" }
                    val effectiveName = (selection.alias ?: selection.name)
                    val joinName = "${id}$effectiveName"
                    val fqPredicate =
                        selection.getDirectives("context").firstOrNull()
                            ?.getArgument("iri")?.value?.let { (it as StringValue).value }
                            ?: throw IllegalArgumentException("Missing semantic context for field '${selection.name}' (${selection.sourceLocation})!")
                    val fieldJoinClause =
                        "JOIN (SELECT s, o FROM $database WHERE p = '$fqPredicate') $joinName ON $joinName.s = $dataAlias.s"
                    if (selection.selectionSet != null) {
                        // Workaround to make sure that null results are filtered out when the field is not optional.
                        if (!isOptional) {
                            nestedNonNullFields.add(effectiveName)
                        }
                        fieldJoinClauses.add(fieldJoinClause)
                        // Field has a nested selection set, recurse
                        "NEST_MANY(${
                            mapNode(
                                selection,
                                joinName,
                                effectiveName.plus("_")
                            )
                        }) AS `$effectiveName`"
                    } else {
                        fieldJoinClauses.add(if (isOptional) "LEFT ".plus(fieldJoinClause) else fieldJoinClause)
                        // Field is a leaf node
                        "$joinName.o AS `$effectiveName`"
                    }
                }

                else -> throw IllegalArgumentException("Unsupported selection type: $selection")
            }
        }
        val dataSelection =
            "SELECT $dataSelectClauses FROM ($subjectSelection) $dataAlias ${fieldJoinClauses.joinToString(" ")}"
        return nestedNonNullFields.takeIf { it.isNotEmpty() }
            ?.let { fieldsToCheck -> "SELECT * FROM ($dataSelection) ${id}env WHERE ${fieldsToCheck.joinToString(" AND ") { "`$it`[1] IS NOT null" }}" }
            ?: dataSelection
    }
}