package kvasir.plugins.kg.xtdb.query

import cz.jirutka.rsql.parser.RSQLParser
import graphql.language.*
import jakarta.ws.rs.BadRequestException
import kvasir.definitions.graphql.Constants
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.rdf.RDFSVocab
import kvasir.definitions.rdf.RDFVocab
import kvasir.plugins.kg.xtdb.dbNameForPod

// TODO: rework/refactor. Split state and logic into smaller class instances (integrate with QueryScope concept)
class GraphQLToSQL(private val request: QueryRequest) {

    private val targetGraphsClause =
        request.targetGraphs.takeIf { it.isNotEmpty() }?.joinToString(",", prefix = "g IN (", postfix = ")") { "'$it'" }
    private val database = dbNameForPod(request.podId)

    // This mapping is required because Xtdb modifies the field names in the query result
    // So to prevent errors and undertermined behavior, we need to keep track of the original field names
    val fieldMapping = mutableMapOf<String, String>()

    fun toSQL(): String {
        // Verify that only a single query is specified and assign it (otherwise throw an exception)
        val rootNode = request.graphQL.definitions.filterIsInstance<OperationDefinition>()
            .firstOrNull { it.operation == OperationDefinition.Operation.QUERY }
            ?: throw IllegalArgumentException("Only one query is allowed")

        val fieldJoinClauses = mutableListOf<String>()
        val nestedNonNullFields = mutableListOf<NestedNonNullFieldCheck>()
        val introspection = GraphQLIntrospection(request)

        return rootNode.selectionSet.selections.filterIsInstance<Field>().mapIndexed { index, selection ->
            when (selection.name) {
                "__schema" -> introspection.schema(selection)
                "__type" -> introspection.type(selection)
                else -> mapSelection(index, selection, QueryScope(0), nestedNonNullFields, fieldJoinClauses)
            }
        }.joinToString(", ", "SELECT ", ";") { it }
    }

    private fun mapNode(
        node: AbstractNode<*>,
        subjectSelection: String,
        queryScope: QueryScope = QueryScope(0)
    ): String {
        val selectionSetContainer = node as SelectionSetContainer<*>
        val fieldJoinClauses = mutableListOf<String>()
        val nestedNonNullFields = mutableListOf<NestedNonNullFieldCheck>()
        val dataSelectClauses = selectionSetContainer.selectionSet.selections.mapIndexed { index, selection ->
            mapSelection(
                index,
                selection,
                queryScope,
                nestedNonNullFields,
                fieldJoinClauses
            )
        }.joinToString(", ")
        val dataSelection =
            "SELECT $dataSelectClauses FROM ($subjectSelection) ${queryScope.dataId()} ${fieldJoinClauses.joinToString(" ")}"
        return nestedNonNullFields.takeIf { it.isNotEmpty() }
            ?.let { fieldsToCheck ->
                "SELECT * FROM ($dataSelection) ${queryScope.envelopeId()} WHERE ${fieldsToCheck.joinToString(" AND ") { "`${it.name}`${if (it.hasMultipleResults) "[1]" else ""} IS NOT null" }}"
            }
            ?: dataSelection
    }

    private fun mapSelection(
        selectionId: Int,
        selection: Selection<*>?,
        queryScope: QueryScope,
        nestedNonNullFields: MutableList<NestedNonNullFieldCheck>,
        fieldJoinClauses: MutableList<String>,
        fqTypeBound: String? = null,
        optionalOverride: Boolean? = null,
        dataIdOverride: String? = null
    ): String {
        when (selection) {
            is Field -> {
                val effectiveName = queryScope.fieldNameKey(selectionId)
                fieldMapping[effectiveName] = selection.alias ?: selection.name
                val joinId = queryScope.joinId(selectionId)
                if (selection.name == "id") {
                    if (queryScope.parent == null) {
                        throw BadRequestException("Field 'id' cannot be used at the top-level. Specify a type as entry-point.")
                    }
                    return "${queryScope.dataId()}.s AS `$effectiveName`" // TODO: cleaner way of shortcutting logic when field is 'id'
                }

                val hasMultipleResults = !selection.hasDirective("single")
                return if (selection.selectionSet != null) {
                    processNestedNode(
                        nestedNonNullFields,
                        effectiveName,
                        hasMultipleResults,
                        fqTypeBound,
                        selection,
                        queryScope,
                        selectionId,
                        optionalOverride,
                        dataIdOverride
                    )
                } else {
                    processLeafNode(
                        fqTypeBound,
                        selection,
                        fieldJoinClauses,
                        joinId,
                        queryScope,
                        effectiveName,
                        hasMultipleResults,
                        optionalOverride,
                        dataIdOverride
                    )
                }
            }

            is InlineFragment -> {
                // Inline fragment, treat included selection set as fields, but with an additional type condition
                val fqType = selection.getContextIRI()
                return selection.selectionSet.selections.mapIndexed { index, inlineSelection ->
                    mapSelection(
                        index,
                        inlineSelection,
                        queryScope.copy(seqNr = queryScope.seqNr + index + 1),
                        nestedNonNullFields,
                        fieldJoinClauses,
                        fqType,
                        optionalOverride = true,
                        dataIdOverride = queryScope.dataId()
                    )
                }.joinToString(", ")
            }

            is FragmentSpread -> {
                // Fragment spread, lookup FragmentDefinition...
                val fragmentDefinition = request.graphQL.getDefinitionsOfType(FragmentDefinition::class.java)
                    .firstOrNull { it.name == selection.name }
                    ?: throw IllegalArgumentException("Fragment definition for '${selection.name}' not found")

                //... and treat included selection set as fields, but with an additional type condition )
                val fqType = fragmentDefinition.getContextIRI()
                return fragmentDefinition.selectionSet.selections.mapIndexed { index, inlineSelection ->
                    mapSelection(
                        index,
                        inlineSelection,
                        queryScope.copy(seqNr = queryScope.seqNr + index + 1),
                        nestedNonNullFields,
                        fieldJoinClauses,
                        fqType,
                        optionalOverride = true,
                        dataIdOverride = queryScope.dataId()
                    )
                }.joinToString(", ")
            }

            else -> throw IllegalArgumentException("Unsupported selection type: $selection")
        }
    }

    private fun processLeafNode(
        fqTypeBound: String?,
        selection: Field,
        fieldJoinClauses: MutableList<String>,
        joinId: String,
        queryScope: QueryScope,
        effectiveName: String,
        hasMultipleResults: Boolean,
        optionalOverride: Boolean? = null,
        dataIdOverride: String? = null
    ): String {
        // Throw exception if top-level leaf nodes are encountered, and a directive specifying different handling is missing, TODO
        if (queryScope.parent == null) {
            throw BadRequestException("Field '${selection.name}' must have a selection of subfields.")
        }

        // Field is a leaf node: use join to select objects matching the specified subject & predicate
        val dataId = dataIdOverride ?: queryScope.dataId()
        val isOptional = optionalOverride ?: selection.directives.any { it.name == "optional" }
        val typeCondition = fqTypeBound?.let { getTypeWhereCondition(it, "s") }
        val whereClause = listOfNotNull(
            targetGraphsClause,
            typeCondition
        )
        val whereClauseStr =
            whereClause.takeIf { it.isNotEmpty() }?.joinToString(" AND ", prefix = "WHERE ") ?: ""
        return when (selection.name) {
            Constants.FIELD_NAMES -> {
                // Special introspection field to return the field names of the current selection
                fieldJoinClauses.add("JOIN (SELECT s, '${Constants.FIELD_NAMES}' as p, ARRAY_AGG(p) as o FROM $database $whereClauseStr) $joinId ON $joinId.s = ${dataId}.s")
                "$joinId.o AS `$effectiveName`"
            }

            Constants.Pagination.TOTAL_COUNT -> {
                // Special introspection field to return the total count of the current selection
                fieldJoinClauses.add("JOIN (SELECT s, '${Constants.Pagination.TOTAL_COUNT}' as p ,COUNT(DISTINCT s) as o FROM $database $whereClauseStr) $joinId ON $joinId.s = ${dataId}.s")
                "$joinId.o AS `$effectiveName`"
            }

            else -> {
                val whereClauseWithPredicateFilter = whereClause.plus(
                    "p = '${selection.getContextIRI()}'"
                ).joinToString(" AND ", prefix = "WHERE ")
                val fieldJoinClause =
                    "JOIN (SELECT s, ARRAY_AGG(o) as ${joinId}_o FROM $database $whereClauseWithPredicateFilter) $joinId ON $joinId.s = ${dataId}.s"
                fieldJoinClauses.add(if (isOptional) "LEFT ".plus(fieldJoinClause) else fieldJoinClause)
                "${joinId}_o${if (!hasMultipleResults) "[1]" else ""} AS `$effectiveName`"
            }
        }
    }

    private fun processNestedNode(
        nestedNonNullFields: MutableList<NestedNonNullFieldCheck>,
        effectiveName: String,
        hasMultipleResults: Boolean,
        fqTypeBound: String?,
        selection: Field,
        queryScope: QueryScope,
        selectionId: Int,
        optionalOverride: Boolean? = null,
        dataIdOverride: String? = null
    ): String {
        // Field has a nested selection set, recurse
        val dataId = dataIdOverride ?: queryScope.dataId()
        val isOptional = optionalOverride ?: selection.directives.any { it.name == "optional" }

        // Workaround to make sure that null results are filtered out when the field is not optional.
        if (!isOptional) {
            nestedNonNullFields.add(NestedNonNullFieldCheck(effectiveName, hasMultipleResults))
        }

        // Calculate additional type condition (if any)
        val typeCondition = fqTypeBound?.let { getTypeWhereCondition(it, "o") }

        // For top-level fields, subjectSelection is based on type matches (unless a directive specifies otherwise, TODO)
        return if (queryScope.parent == null) {
            val typeFilter = selection.getContextIRI().takeIf { it != RDFSVocab.Resource }
                ?.let { "s IN (SELECT s FROM $database WHERE p = '${RDFVocab.type}' AND o = '$it')" }
            val whereClause = listOfNotNull(
                targetGraphsClause,
                typeFilter,
                getAdditionalConditions(selection, "s"),
            ).takeIf { it.isNotEmpty() }?.joinToString(" AND ", prefix = "WHERE ") ?: ""
            "${if (hasMultipleResults) "NEST_MANY" else "NEST_ONE"}(${
                mapNode(
                    selection,
                    "SELECT DISTINCT s AS s FROM $database $whereClause${if (!hasMultipleResults) " LIMIT 1" else ""}",
                    queryScope.nestedScope(selectionId)
                )
            }) AS `$effectiveName`"
        } else {
            // When not top-level: specify a subjectSelection based on the objects matching the specified subject & predicate)
            val whereClause = listOfNotNull(
                targetGraphsClause,
                "s = ${dataId}.s AND p = '${selection.getContextIRI()}'",
                typeCondition,
                getAdditionalConditions(selection, "o"),
            ).joinToString(" AND ", prefix = "WHERE ")
            "${if (hasMultipleResults) "NEST_MANY" else "NEST_ONE"}(${
                mapNode(
                    selection,
                    "SELECT DISTINCT o AS s FROM $database $whereClause${if (!hasMultipleResults) " LIMIT 1" else ""}",
                    queryScope.nestedScope(selectionId)
                )
            }) AS `$effectiveName`"
        }
    }

    private fun getTypeWhereCondition(fqTypeBound: String, targetColumn: String): String? {
        if (fqTypeBound == RDFSVocab.Resource) {
            // No need to filter on type if the type is Resource (matches all RDF classes)
            return null
        }
        return "$targetColumn IN (SELECT s FROM $database WHERE p = '${RDFVocab.type}' AND o = '$fqTypeBound')"
    }

    private fun getAdditionalConditions(selection: Field, targetColumn: String): String? {
        // Process id argument separately, as it is a special case
        val idCondition = selection.arguments.find { it.name == "id" }?.let {
            if (it.value is ArrayValue) {
                val ids = (it.value as ArrayValue).values.map { arrayItem -> toSQLValue(arrayItem) }
                "$targetColumn IN (${ids.joinToString(",")}) "
            } else {
                "$targetColumn = ${toSQLValue(it.value)} "
            }
        }

        // Then process other arguments, plus conditions coming from @filter directives
        val conditions = selection.arguments.filterNot { it.name == "id" }.map {
            val fqArgName = it.additionalData["iri"] as String
            val objectFilter = if (it.value is ArrayValue) {
                "o IN (${(it.value as ArrayValue).values.joinToString(",") { arrayItem -> toSQLValue(arrayItem) }})"
            } else {
                "o = ${toSQLValue(it.value)}"
            }
            "p = '$fqArgName' AND $objectFilter"
        }.plus(getNodeFilter(selection))
            .joinToString(" AND ")

        return listOfNotNull(
            idCondition, conditions
        ).joinToString(" AND ", "$targetColumn IN (SELECT s FROM $database WHERE ", ")")
    }

    private fun getNodeFilter(field: Field): List<String> {
        val subFields = field.selectionSet?.selections?.filterIsInstance<Field>()
        val predicateMapping = (subFields?.associate { it.name to it.getContextIRI() } ?: emptyMap())
        val filters = subFields?.mapNotNull { subField ->
            subField.directives.firstOrNull { it.name == "filter" }?.let { directive ->
                val rsqlExpr = directive.getArgument("if")?.value?.let { (it as StringValue).value }
                    ?: throw IllegalArgumentException("Missing 'if' argument containing RSQL expression on filter directive")
                val rsqlParser = RSQLParser()
                val rsqlNode = rsqlParser.parse(rsqlExpr)
                rsqlNode.accept(
                    GraphQLFilterVisitor(rsqlExpr, predicateMapping.plus("it" to subField.getContextIRI()))
                )
            }
        }
        return filters ?: emptyList()
    }

}

private fun <T : DirectivesContainer<T>> DirectivesContainer<T>.getContextIRI(): String {
    return getDirectives("context").firstOrNull()?.getArgument("iri")?.value?.let { (it as StringValue).value }
        ?: throw IllegalArgumentException("Missing semantic context for ${this::class.simpleName} at ${sourceLocation}!")
}

private fun toSQLValue(value: Value<*>): String {
    return when (value) {
        is FloatValue -> value.value.toString()
        is IntValue -> value.value.toString()
        is BooleanValue -> value.isValue.toString()
        is StringValue -> "'${value.value}'"
        else -> throw IllegalArgumentException("Unsupported value type: $value")
    }
}

data class NestedNonNullFieldCheck(val name: String, val hasMultipleResults: Boolean)

data class QueryScope(val seqNr: Int, val parent: QueryScope? = null) {
    fun scopedSeqNr(): String = "${if (parent == null) "s" else ""}${parent?.scopedSeqNr()?.plus("_") ?: ""}$seqNr"
    fun dataId(): String = "${scopedSeqNr()}_d"
    fun envelopeId(): String = "${scopedSeqNr()}_e"
    fun joinId(index: Int): String = "${scopedSeqNr()}_j$index"
    fun nestedScope(index: Int): QueryScope = QueryScope(index, this)
    fun fieldNameKey(index: Int): String = "${scopedSeqNr()}_f$index"
}