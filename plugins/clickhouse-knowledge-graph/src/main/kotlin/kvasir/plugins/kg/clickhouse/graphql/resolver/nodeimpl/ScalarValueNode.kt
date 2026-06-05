package kvasir.plugins.kg.clickhouse.graphql.resolver.nodeimpl

import cz.jirutka.rsql.parser.ast.ComparisonNode
import cz.jirutka.rsql.parser.ast.Node
import cz.jirutka.rsql.parser.ast.RSQLOperators
import graphql.Scalars
import graphql.language.BooleanValue
import graphql.language.Field
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLNamedType
import kvasir.definitions.kg.graphql.*
import kvasir.definitions.persistence.SortOrder
import kvasir.plugins.kg.clickhouse.graphql.SELF_REF_SELECTOR
import kvasir.plugins.kg.clickhouse.graphql.SelectorReplacingFilterVisitor
import kvasir.plugins.kg.clickhouse.graphql.newFilterParser
import kvasir.plugins.kg.clickhouse.graphql.resolver.*
import kvasir.plugins.kg.clickhouse.specs.COLLAPSE_EXPR
import kvasir.plugins.kg.clickhouse.specs.CURRENT_DATA_TABLE
import kvasir.plugins.kg.clickhouse.specs.DATA_TABLE
import kvasir.utils.graphql.*

internal const val RELATIONS_PROJ_TARGET = "target"

/**
 * A node representing a scalar field.
 * This requires a JOIN to the matching relation CTE, as well as aggregation with groupUniqArray in the parent node.
 */
class ScalarCollectionNode(
    val field: Field,
    val fieldDefinition: GraphQLFieldDefinition,
    val parent: CompositeNode,
    val overrideJoinType: String? = null
) : JoinableNode, NodeWithFilterForParent {

    override val name: String = fieldDefinition.name
    override val nameInResult: String = field.alias ?: field.name
    override val joinIdentifier = getVariableNameForField(fieldDefinition, parent.context) + "_col"
    val paginationInfo = field.getPaginationInfo(parent.env.variables)
    val sortOrder = field.getStringArgument(ARG_SORT_NAME, parent.env.variables)?.let { SortOrder.valueOf(it) }

    override fun getJoinStatements(): List<String> {
        // LEFT JOIN if the field is optional, otherwise (INNER) JOIN
        val joinType =
            overrideJoinType ?: (if (isOptional(field, fieldDefinition)) "LEFT " else "")
        val paginationProj = paginationInfo?.let { "count() OVER (PARTITION BY id) as $COUNT" }
        val (relTableId, projection) = when (name) {
            FIELD_PREDICATES_NAME -> {
                val relTableId = parent.atChangeId?.let {
                    // Time travel query: collapse state
                    "$DATA_TABLE WHERE change_id <= '$it' $COLLAPSE_EXPR"
                } ?: "$CURRENT_DATA_TABLE FINAL WHERE sign = 1" // Else use current state
                val projection =
                    listOfNotNull(
                        "subject as id",
                        "predicate as value",
                        "datatype",
                        paginationProj
                    ).joinToString()
                relTableId to projection
            }
            FIELD_RELATIONS_NAME -> {
                val relTableId = parent.atChangeId?.let {
                    // Time travel query: collapse state
                    "$DATA_TABLE WHERE datatype = '' AND change_id <= '$it' $COLLAPSE_EXPR"
                } ?: "$CURRENT_DATA_TABLE FINAL WHERE datatype = '' AND sign = 1" // Else use current state
                val projection =
                    listOfNotNull(
                        "subject as id",
                        "predicate as value",
                        "object as $RELATIONS_PROJ_TARGET",
                        "datatype",
                        paginationProj
                    ).joinToString()
                relTableId to projection
            }

            else -> {
                val fqFieldName = getFQName(fieldDefinition, parent.context)
                // Check if the relation is reversed based on the presence of the @predicate directive with reverse: true
                // TODO: make reverse work when defined in context vs. in the graphql schema
                val reverse = fieldDefinition.getDirectiveArg<BooleanValue>(
                    DIRECTIVE_PREDICATE_NAME,
                    ARG_REVERSE_NAME
                )?.isValue ?: false
                val relTableId = parent.atChangeId?.let {
                    // Time travel query: collapse state
                    "$DATA_TABLE WHERE predicate = '$fqFieldName' AND change_id <= '$it' $COLLAPSE_EXPR"
                } ?: "$CURRENT_DATA_TABLE FINAL WHERE predicate = '$fqFieldName' AND sign = 1" // Else use current state
                val projection =
                    listOfNotNull(
                        if(!reverse) "subject as id" else "object as id",
                        if(!reverse) "object as value" else "subject as value",
                        "datatype",
                        paginationProj
                    ).joinToString()
                relTableId to projection
            }
        }

        val limit = paginationInfo?.let { (pageSize, offset) -> " LIMIT $offset, $pageSize BY id" } ?: ""
        val orderBy = sortOrder?.let { "ORDER BY value $it " } ?: ""
        return listOf(
            "$joinType JOIN (SELECT $projection FROM $relTableId $orderBy$limit) AS $joinIdentifier ON ${parent.scope}.subject = $joinIdentifier.id"
        )
    }

    override fun getNodeFilter(): Node? {
        // Get optional field filter
        return getFilter(field, fieldDefinition, parent.env)?.let { rsql ->
            // Replace references to the field itself in the filter expression with a reference to the field as projected in the joined relation CTE, so that the filter can be correctly applied in the context of the joined table.
            SelectorReplacingFilterVisitor(
                setOf(name, nameInResult, SELF_REF_SELECTOR),
                nameInResult,
                true
            ).visitNode(
                try {
                    newFilterParser().parse(rsql)
                } catch (e: Exception) {
                    throw IllegalArgumentException("Failed to parse RSQL filter for field '$name': $rsql", e)
                }
            )
        }
    }

    override fun getArgFilter(): Node? {
        // If the node represents the "_relations" field, add handling for the id argument filter
        return field.getStringArgument(ARG_ID_NAME, parent.env.variables)?.takeIf { name == FIELD_RELATIONS_NAME }
            ?.let { ComparisonNode(RSQLOperators.EQUAL, RELATIONS_PROJ_TARGET, listOf(it)) }
    }

    override fun isPaginated(): Boolean = paginationInfo != null

    override fun buildProjection(): String {
        val innerArray = if (fieldDefinition.type.innerType<GraphQLNamedType>() == Scalars.GraphQLID) {
            // Special handling for fields that return IDs: empty strings caused by the LEFT JOIN for optional fields should be filtered out, as they do not represent actual values but just the absence of a relation.
            "groupUniqArrayIf($joinIdentifier.value, $joinIdentifier.value != '') AS $nameInResult"
        } else {
            "groupUniqArrayIf($joinIdentifier.value, $joinIdentifier.id != '') AS $nameInResult"
        }
        return sortOrder?.let {
            when (it) {
                SortOrder.ASC -> "arraySort($innerArray)"
                SortOrder.DESC -> "arrayReverseSort($innerArray)"
            }
        } ?: innerArray
    }

    override fun isGroupingKey(): Boolean {
        // Collection fields should not be grouping keys, as they are aggregated with groupUniqArray
        return false
    }
}

/**
 * A node representing a synthetic scalar field, not present in the query document,
 * but generated by the resolver for various purposes.
 */
class SyntheticScalarNode(
    override val name: String,
    val parent: CompositeNode,
    val overrideExpr: String? = null,
    val groupingKey: Boolean = true,
    val includeInResultMap: Boolean = true
) : QueryTreeNode {

    override val nameInResult: String = name

    override fun buildProjection() = "${overrideExpr ?: "${parent.scope}.$nameInResult"} AS $nameInResult"

    override fun isGroupingKey() = groupingKey

    override fun isIncludeInResultMapping() = includeInResultMap
}