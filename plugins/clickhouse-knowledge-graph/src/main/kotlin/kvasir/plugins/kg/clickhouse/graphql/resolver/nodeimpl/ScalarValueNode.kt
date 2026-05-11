package kvasir.plugins.kg.clickhouse.graphql.resolver.nodeimpl

import cz.jirutka.rsql.parser.RSQLParser
import cz.jirutka.rsql.parser.ast.ComparisonNode
import cz.jirutka.rsql.parser.ast.Node
import cz.jirutka.rsql.parser.ast.RSQLOperators
import graphql.Scalars
import graphql.language.Field
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLNamedType
import kvasir.definitions.kg.graphql.ARG_ID_NAME
import kvasir.definitions.kg.graphql.ARG_SORT_NAME
import kvasir.definitions.kg.graphql.FIELD_RELATIONS_NAME
import kvasir.definitions.persistence.SortOrder
import kvasir.definitions.rdf.JSONObject
import kvasir.plugins.kg.clickhouse.graphql.SELF_REF_SELECTOR
import kvasir.plugins.kg.clickhouse.graphql.SelectorReplacingFilterVisitor
import kvasir.plugins.kg.clickhouse.graphql.ToSQLFilterVisitor
import kvasir.plugins.kg.clickhouse.graphql.resolver.*
import kvasir.utils.graphql.getPaginationInfo
import kvasir.utils.graphql.getStringArgument
import kvasir.utils.graphql.innerType

/**
 * A node representing a scalar field that has a multiplicity of 0..1 (i.e. a single value, not a collection).
 */
class ScalarValueNode(
    val field: Field, val fieldDefinition: GraphQLFieldDefinition, val parent: CompositeNode
) : NodeWithFilterForParent {

    override val name: String = fieldDefinition.name
    override val nameInResult: String = field.alias ?: field.name

    override fun getNodeFilter(): Node? {
        return getFilter(field, fieldDefinition, parent.env)?.let {
            // Replace references to the field itself in the filter expression with a reference to the field as projected in the parent node, so that the filter can be correctly applied in the parent node's SQL query.
            SelectorReplacingFilterVisitor(setOf(name, nameInResult, SELF_REF_SELECTOR), nameInResult, true).visitNode(
                RSQLParser().parse(it)
            )
        }
    }

    override fun buildProjection() = "${parent.scope}.${fieldDefinition.name} AS $nameInResult"
}

/**
 * A node representing a scalar field that has a multiplicity of 0..* (i.e. a collection of values).
 * This requires a JOIN to the matching relation CTE, as well as aggregation with groupUniqArray in the parent node.
 */
class ScalarCollectionNode(
    val field: Field,
    val fieldDefinition: GraphQLFieldDefinition,
    val parent: CompositeNode,
    val context: JSONObject,
    val overrideJoinType: String? = null
) : JoinableNode, NodeWithRelationRefs, NodeWithFilterForParent {

    override val name: String = fieldDefinition.name
    override val nameInResult: String = field.alias ?: field.name
    override val joinIdentifier = getVariableNameForField(fieldDefinition, context) + "_col"
    val paginationInfo = field.getPaginationInfo(parent.env.variables, fieldDefinition)
    val sortOrder = field.getStringArgument(ARG_SORT_NAME, parent.env.variables)?.let { SortOrder.valueOf(it) }

    override fun getJoinStatements(): List<String> {
        // LEFT JOIN if the field is optional, otherwise (INNER) JOIN
        val joinType =
            overrideJoinType ?: (if (isOptional(field, fieldDefinition)) "LEFT " else "")
        val relTableId = RelationInfo(
            field,
            fieldDefinition,
            parent.type,
            context
        ).identifier

        val projection =
            listOfNotNull(
                "id",
                "value",
                "datatype",
                RELATIONS_PROJ_TARGET.takeIf { fieldDefinition.name == FIELD_RELATIONS_NAME },
                paginationInfo?.let { "count() OVER (PARTITION BY id) as $COUNT" }).joinToString()
        val limit = paginationInfo?.let { (pageSize, offset) -> " LIMIT $offset, $pageSize BY id" } ?: ""
        val orderBy = sortOrder?.let { "ORDER BY value $it " } ?: ""
        val where = relationFilter("value")?.let { "WHERE ${ToSQLFilterVisitor(context).visitNode(it)} " } ?: ""
        return listOf(
            "$joinType JOIN (SELECT $projection FROM $relTableId $where$orderBy$limit) AS $joinIdentifier ON ${parent.scope}.id = $joinIdentifier.id"
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
                RSQLParser().parse(rsql)
            )
        }
    }

    override fun getArgFilter(): Node? {
        // If the node represents the "_relations" field, add handling for the id argument filter
        return field.getStringArgument(ARG_ID_NAME, parent.env.variables)?.takeIf { name == FIELD_RELATIONS_NAME }
            ?.let { ComparisonNode(RSQLOperators.EQUAL, RELATIONS_PROJ_TARGET, listOf(it)) }
    }

    override fun getRelationRefs(): List<RelationInfo> {
        // Signal the relation CTE that this node is referencing by returning the following RelationInfo.
        return listOf(RelationInfo(field, fieldDefinition, parent.type, context, relationFilter("object")))
    }

    override fun isPaginated(): Boolean = paginationInfo != null

    override fun buildProjection(): String {
        val innerArray = if (fieldDefinition.type.innerType<GraphQLNamedType>() == Scalars.GraphQLID) {
            // Special handling for fields that return IDs: empty strings caused by the LEFT JOIN for optional fields should be filtered out, as they do not represent actual values but just the absence of a relation.
            "groupUniqArrayIf($joinIdentifier.value, $joinIdentifier.value != '') AS $nameInResult"
        } else {
            "groupUniqArray($joinIdentifier.value) AS $nameInResult"
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

    private fun relationFilter(targetSelector: String): Node? {
        return getFilter(field, fieldDefinition, parent.env)?.let { rsql ->
            SelectorReplacingFilterVisitor(
                setOf(name, nameInResult, SELF_REF_SELECTOR),
                targetSelector,
                true
            ).visitNode(RSQLParser().parse(rsql))
        }
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
