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
import kvasir.plugins.kg.clickhouse.graphql.ToSQLFilterVisitor
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
) : JoinableNode, NodeWithFilterForParent, NodeWithSubjectConstraint {

    override val name: String = fieldDefinition.name
    override val nameInResult: String = field.alias ?: field.name
    override val joinIdentifier = getVariableNameForField(fieldDefinition, parent.context) + "_col"
    val paginationInfo = field.getPaginationInfo(parent.env.variables)
    val sortOrder = field.getStringArgument(ARG_SORT_NAME, parent.env.variables)?.let { SortOrder.valueOf(it) }
    private val parsedFilter = getFilter(field, fieldDefinition, parent.env)?.let { rsql ->
        try {
            newFilterParser().parse(rsql)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse RSQL filter for field '$name': $rsql", e)
        }
    }
    private val parentFilter = parsedFilter?.let { filter ->
        SelectorReplacingFilterVisitor(
            setOf(name, nameInResult, SELF_REF_SELECTOR),
            nameInResult,
            true
        ).visitNode(filter)
    }
    private val relationValueFilter = parsedFilter?.let { filter ->
        SelectorReplacingFilterVisitor(
            setOf(name, nameInResult, SELF_REF_SELECTOR),
            "value",
            true
        ).visitNode(filter)
    }

    private val reverse = fieldDefinition.getDirectiveArg<BooleanValue>(
        DIRECTIVE_PREDICATE_NAME,
        ARG_REVERSE_NAME
    )?.isValue ?: false

    override fun getJoinStatements(): List<String> {
        // LEFT JOIN if the field is optional, otherwise (INNER) JOIN
        val joinType =
            overrideJoinType ?: (if (isOptional(field, fieldDefinition)) "LEFT " else "")
        val paginationProj = paginationInfo?.let { "count() OVER (PARTITION BY id) as $COUNT" }
        val (relTableId, baseProjection) = getRelationScan(parent.subjectConstraints, relationValueFilter)
        val projection =
            listOfNotNull(
                "id",
                "value",
                RELATIONS_PROJ_TARGET.takeIf { name == FIELD_RELATIONS_NAME },
                "datatype",
                paginationProj
            ).joinToString()
        val relationFilter = relationValueFilter?.let { " WHERE ${ToSQLFilterVisitor(parent.context).visitNode(it)}" } ?: ""
        val limit = paginationInfo?.let { (pageSize, offset) -> " LIMIT $offset, $pageSize BY id" } ?: ""
        val orderBy = sortOrder?.let { " ORDER BY value $it" }
            ?: paginationInfo?.let { " ORDER BY id ASC, value ASC" }
            ?: ""
        return listOf(
            "$joinType JOIN (SELECT $projection FROM (SELECT $baseProjection FROM $relTableId) AS ${joinIdentifier}_src$relationFilter$orderBy$limit) AS $joinIdentifier ON ${parent.scope}.subject = $joinIdentifier.id"
        )
    }

    override fun getNodeFilter(): Node? {
        return parentFilter
    }

    override fun getSubjectConstraint(): SubjectConstraint? {
        val filter = relationValueFilter ?: return null
        val (relTableId, baseProjection) = getRelationScan(emptyList(), filter)
        return SubjectConstraint(
            "SELECT id FROM (SELECT $baseProjection FROM $relTableId) AS ${joinIdentifier}_candidates GROUP BY id"
        )
    }

    override fun getArgFilter(): Node? {
        // If the node represents the "_relations" field, add handling for the id argument filter
        return field.getStringArgument(ARG_ID_NAME, parent.env.variables)?.takeIf { name == FIELD_RELATIONS_NAME }
            ?.let { ComparisonNode(RSQLOperators.EQUAL, RELATIONS_PROJ_TARGET, listOf(it)) }
    }

    override fun isPaginated(): Boolean = paginationInfo != null

    override fun buildProjection(): String {
        val arrayExpr = if (fieldDefinition.type.innerType<GraphQLNamedType>() == Scalars.GraphQLID) {
            // Special handling for fields that return IDs: empty strings caused by the LEFT JOIN for optional fields should be filtered out, as they do not represent actual values but just the absence of a relation.
            "groupUniqArrayIf($joinIdentifier.value, $joinIdentifier.value != '')"
        } else {
            "groupUniqArrayIf($joinIdentifier.value, $joinIdentifier.id != '')"
        }
        val sortedArrayExpr = sortOrder?.let {
            when (it) {
                SortOrder.ASC -> "arraySort($arrayExpr)"
                SortOrder.DESC -> "arrayReverseSort($arrayExpr)"
            }
        } ?: "arraySort($arrayExpr)"
        return "$sortedArrayExpr AS $nameInResult"
    }

    override fun isGroupingKey(): Boolean {
        // Collection fields should not be grouping keys, as they are aggregated with groupUniqArray
        return false
    }

    private fun getRelationScan(
        subjectConstraints: Collection<SubjectConstraint>,
        valueFilter: Node? = null
    ): Pair<String, String> {
        val scan = when (name) {
            FIELD_PREDICATES_NAME -> RelationScan(
                baseConditions = emptyList(),
                idExpression = "subject",
                valueExpression = "predicate",
                subjectConstraintExpression = "subject"
            )

            FIELD_RELATIONS_NAME -> RelationScan(
                baseConditions = listOf("datatype = ''"),
                idExpression = "subject",
                valueExpression = "predicate",
                extraProjections = listOf("object as $RELATIONS_PROJ_TARGET"),
                subjectConstraintExpression = "subject"
            )

            else -> {
                val fqFieldName = getFQName(fieldDefinition, parent.context)
                RelationScan(
                    baseConditions = listOf("predicate = '$fqFieldName'"),
                    idExpression = if (!reverse) "subject" else "object",
                    valueExpression = if (!reverse) "object" else "subject",
                    subjectConstraintExpression = if (!reverse) "subject" else "object"
                )
            }
        }
        val pushedValueFilter = valueFilter?.let { filter ->
            val filterForStorageColumn = SelectorReplacingFilterVisitor(
                "value",
                scan.valueExpression,
                true
            ).visitNode(filter)
            ToSQLFilterVisitor(parent.context).visitNode(filterForStorageColumn)
        }
        val source = relationSource(scan, subjectConstraints, pushedValueFilter)
        val projection = listOf(
            "${scan.idExpression} as id",
            "${scan.valueExpression} as value",
            *scan.extraProjections.toTypedArray(),
            "datatype"
        ).joinToString()
        return source to projection
    }

    private fun relationSource(
        scan: RelationScan,
        subjectConstraints: Collection<SubjectConstraint>,
        pushedValueFilter: String?
    ): String {
        val subjectConstraint = buildSubjectConstraintCondition(
            scan.subjectConstraintExpression,
            subjectConstraints,
            parent.context
        )
        val prewhereConditions = listOfNotNull(
            *scan.baseConditions.toTypedArray(),
            pushedValueFilter
        )
        val prewhere = prewhereConditions
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" AND ", " PREWHERE ")
            ?: ""
        val historicalConditions = listOfNotNull(
            parent.atChangeId?.let { "change_id <= '$it'" },
            subjectConstraint
        )
        return parent.atChangeId?.let {
            val where = historicalConditions
                .takeIf { historicalConditions.isNotEmpty() }
                ?.joinToString(" AND ", " WHERE ")
                ?: ""
            "$DATA_TABLE$prewhere$where $COLLAPSE_EXPR"
        } ?: run {
            val currentConditions = listOfNotNull(
                "sign = 1",
                subjectConstraint
            )
            val where = currentConditions.joinToString(" AND ", " WHERE ")
            "$CURRENT_DATA_TABLE FINAL$prewhere$where"
        }
    }

    private data class RelationScan(
        val baseConditions: List<String>,
        val idExpression: String,
        val valueExpression: String,
        val subjectConstraintExpression: String,
        val extraProjections: List<String> = emptyList()
    )
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
