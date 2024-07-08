package kvasir.plugins.kg.xtdb

import graphql.language.*
import graphql.parser.Parser
import kvasir.definitions.kg.QueryRequest

class GraphQLToSQL(private val request: QueryRequest) {

    fun toSQL(): String {
        // Verify that only a single query is specified and assign it (otherwise throw an exception)
        val rootNode = request.graphQL.definitions.filterIsInstance<OperationDefinition>()
            .firstOrNull { it.operation == OperationDefinition.Operation.QUERY }
            ?: throw IllegalArgumentException("Only one query is allowed")
        return mapNode(rootNode)
    }


    fun mapNode(
        node: AbstractNode<*>,
        joinId: String? = null,
        id: String = ""
    ): String {
        val dataAlias = "${id}data"
        val subjectsAlias = "${id}subjects"
        val selectionSetContainer = node as SelectionSetContainer<*>
        val subjectWereClause = joinId?.let { " WHERE s = $joinId.o" } ?: ""
        val subjectSelection = "SELECT DISTINCT s FROM ${request.podId}$subjectWereClause"
        val fieldJoinClauses = mutableListOf<String>()
        val selectedFields = mutableListOf<String>()
        val dataSelectClauses = selectionSetContainer.selectionSet.selections.joinToString(", ") { selection ->
            when (selection) {
                is Field -> {
                    val effectiveName = (selection.alias ?: selection.name)
                    val joinName = "${id}$effectiveName"
                    selectedFields.add(effectiveName)
                    val fqPredicate =
                        selection.getDirectives("context").firstOrNull()
                            ?.getArgument("iri")?.value?.let { (it as StringValue).value }
                            ?: throw IllegalArgumentException("Missing semantic context for field '${selection.name}' (${selection.sourceLocation})!")
                    fieldJoinClauses.add("JOIN (SELECT s, o FROM ${request.podId} WHERE p = '$fqPredicate') $joinName ON $joinName.s = $dataAlias.s")
                    if (selection.selectionSet != null) {
                        // Field has a nested selection set, recurse
                        "NEST_MANY(${
                            mapNode(
                                selection,
                                joinName,
                                effectiveName.plus("_")
                            )
                        }) AS $effectiveName"
                    } else {
                        // Field is a leaf node
                        "$joinName.o AS $effectiveName"
                    }
                }

                else -> throw IllegalArgumentException("Unsupported selection type: $selection")
            }
        }
        val dataSelection =
            "SELECT DISTINCT $dataSelectClauses FROM ${request.podId} $dataAlias ${fieldJoinClauses.joinToString(" ")} WHERE $subjectsAlias.s = $dataAlias.s"
        return "SELECT ${selectedFields.joinToString(", ")} FROM ($subjectSelection) $subjectsAlias, LATERAL ($dataSelection) $dataAlias"
    }
}

fun main() {
    val q = Parser.parse(
        """
        {
          name @context(iri: "schema:givenName")
          email @context(iri: "schema:email")
          #friendRel:bestFriend @context(iri: "ex:friends")
          #bestFriend @context(iri: "ex:friends") {
          #  name @context(iri: "schema:givenName")
          #  email @context(iri: "schema:email")
          #}
        }
    """.trimIndent()
    )
    println(GraphQLToSQL(QueryRequest("docs", q)).toSQL())

}