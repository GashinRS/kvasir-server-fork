package kvasir.plugins.kg.clickhouse.graphql.resolver

import cz.jirutka.rsql.parser.RSQLParser
import cz.jirutka.rsql.parser.ast.ComparisonNode
import cz.jirutka.rsql.parser.ast.RSQLOperators
import graphql.Scalars
import graphql.language.BooleanValue
import graphql.language.StringValue
import graphql.scalars.ExtendedScalars
import graphql.schema.*
import kvasir.definitions.kg.DEFAULT_PAGE_SIZE
import kvasir.definitions.kg.graphql.*
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.RDFVocab
import kvasir.plugins.kg.clickhouse.graphql.SELF_REF_SELECTOR
import kvasir.plugins.kg.clickhouse.graphql.SelectorReplacingFilterVisitor
import kvasir.plugins.kg.clickhouse.graphql.ToSQLFilterVisitor
import kvasir.plugins.kg.clickhouse.specs.COLLAPSED_STATE_BY_TYPE_TABLE
import kvasir.plugins.kg.clickhouse.specs.DATA_TABLE
import kvasir.utils.graphql.*

internal const val CHANGE_ID_COLUMN = "_change_id"
internal const val RELATIONS_PROJ_TARGET = "target"

/**
 * Base class for building CTEs for both types and scalar collections. Provides common utilities such as determining
 * which RDF type URIs to match based on the GraphQL type, taking into account interfaces and unions.
 */
abstract class AbstractCTEBuilder(
    protected val context: JSONObject,
    atChangeId: String?,
    protected val env: DataFetchingEnvironment
) {

    protected val atChangeIdExpr = atChangeId?.let { "'$it'" } ?: "''"

    protected fun arrayExpr(values: Iterable<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { "'$it'" }

    protected fun collapsedStateExpr(
        domainClassIRIs: Iterable<String> = emptyList(),
        rangeClassIRIs: Iterable<String> = emptyList(),
        predicateIRIs: Iterable<String> = emptyList(),
        objectIRIs: Iterable<String> = emptyList(),
        subjectConstraintPredicateIRIs: Iterable<String> = emptyList(),
        subjectConstraintObjects: Iterable<String> = emptyList(),
        subjectConstraintLimit: Long = 0
    ): String =
        "$COLLAPSED_STATE_BY_TYPE_TABLE(domainClassIRIs=${arrayExpr(domainClassIRIs)},rangeClassIRIs=${arrayExpr(rangeClassIRIs)},predicateIRIs=${arrayExpr(predicateIRIs)},objectIRIs=${arrayExpr(objectIRIs)},subjectConstraintPredicateIRIs=${arrayExpr(subjectConstraintPredicateIRIs)},subjectConstraintObjects=${arrayExpr(subjectConstraintObjects)},subjectConstraintLimit=$subjectConstraintLimit,at_change_id=$atChangeIdExpr)"

    protected fun objectFilterValues(filter: ComparisonNode?): Set<String> {
        if (filter?.selector != "object" || filter.operator !in setOf(RSQLOperators.EQUAL, RSQLOperators.IN)) {
            return emptySet()
        }
        return filter.arguments.map { JsonLdHelper.getFQName(it, context, ":") ?: it }.toSet()
    }

    protected fun subjectConstraintValues(subjectConstraints: Set<SubjectConstraint>): List<Pair<String, Set<String>>> {
        return subjectConstraints.mapNotNull { constraint ->
            val predicateIRI = getFQName(constraint.relationInfo.fieldDefinition, context)
            val values = objectFilterValues(constraint.filter as? ComparisonNode).takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            predicateIRI to values
        }
    }

    protected fun candidateLimit(): Long {
        val (pageSize, offset) = env.field.getPaginationInfo(env.variables, env.fieldDefinition)
            ?: (DEFAULT_PAGE_SIZE to 0L)
        return offset + maxOf(pageSize + 1L, 1000L)
    }

    protected fun candidateSubjectFilter(subjectConstraints: Set<SubjectConstraint>): String? {
        val values = subjectConstraintValues(subjectConstraints).takeIf { it.isNotEmpty() } ?: return null
        val candidatePredicateIRIs = values.map { it.first }.toSet()
        val candidateObjects = values.flatMap { it.second }.toSet()
        val candidatesExpr = rawCurrentStateExpr(candidatePredicateIRIs, candidateObjects)
        return "subject IN (SELECT subject FROM ($candidatesExpr) LIMIT ${candidateLimit()})"
    }

    protected fun rawCurrentStateExpr(
        predicateIRIs: Iterable<String>,
        objectIRIs: Iterable<String> = emptyList(),
        subjectFilter: String? = null
    ): String {
        val predicates = predicateIRIs.toSet()
        val objects = objectIRIs.toSet()
        val where = listOfNotNull(
            "($atChangeIdExpr = '' OR change_id <= $atChangeIdExpr)",
            predicates.takeIf { it.isNotEmpty() }?.let { "predicate IN ${arrayExpr(it)}" },
            objects.takeIf { it.isNotEmpty() }?.let { "object IN ${arrayExpr(it)}" },
            subjectFilter
        ).joinToString(" AND ", "WHERE ")
        return "SELECT subject, predicate, object, datatype, language, graph, max(change_id) AS $CHANGE_ID_COLUMN " +
            "FROM $DATA_TABLE $where GROUP BY subject, predicate, object, datatype, language, graph " +
            "HAVING argMax(sign, change_id) > 0"
    }

    /**
     * Determines the set of RDF type URIs to match for a given GraphQL composite type.
     * For interfaces and unions, this includes the implementing or member types, respectively.
     * For regular object types, this is just the type itself.
     */
    protected fun getTypeURIsToMatch(type: GraphQLCompositeType): Set<String> {
        return when {
            type.name in setOf(TYPE_RDF_NODE, TYPE_RESOURCE) -> emptyList()
            type is GraphQLInterfaceType -> env.graphQLSchema.getImplementations(type)
            type is GraphQLUnionType -> type.types
            else -> listOf(type)
        }.mapNotNull {
            if (it is GraphQLNamedType && it.name in setOf(TYPE_BOXED_LITERAL, ExtendedScalars.Json.name)) {
                null
            } else {
                getFQName(it as GraphQLDirectiveContainer, context)
            }
        }.toSet()
    }

}

/**
 * Builder for CTEs representing a GraphQL type. Handles collapsing state and time travel,
 * fetches RDF classes for the selected Resources and literal values for scalars that are not a collection.
 */
class TypeCTEBuilder(
    context: JSONObject,
    atChangeId: String?,
    env: DataFetchingEnvironment,
    val typeInfo: TypeInfo
) : AbstractCTEBuilder(context, atChangeId, env) {

    private fun buildProjection(fqPredicateValue: String, alias: String, collectionType: Boolean = true, schemaFilter: String? = null): String {
        val condition = listOfNotNull(
            // Only select objects where the predicate matches the supplied predicate value
            "predicate = '$fqPredicateValue'",
            // Apply filters specified at the Slice schema level
            schemaFilter?.let {
                // Translate RSQL to applicable SQL
                val processedRSQL = SelectorReplacingFilterVisitor(setOf(SELF_REF_SELECTOR, alias), "object", true).visitNode(
                    RSQLParser().parse(it)
                )
                ToSQLFilterVisitor(context).visitNode(processedRSQL)
            }
        ).joinToString(" AND ")
        // If the field is a collection, group the matching objects into an array. If it's a single value, take the most recent one based on change_id.
        val combinator = if (collectionType) {
            "groupUniqArrayIf(object, $condition)"
        } else {
            "argMaxIf(object, $CHANGE_ID_COLUMN, $condition)"
        }
        return "$combinator AS $alias"
    }

    fun build(): String {
        // Always include these projections to support built-in constructs
        val fixedProjections = listOf(
            "subject as id",
            "map('@id',subject) as _rawRDF",
            buildProjection(RDFVocab.type, "_types")
        )
        // Generate projections for the non-built-in fields used in the query.
        val nonNullFields = mutableListOf<String>()
        val typeURIs = getTypeURIsToMatch(typeInfo.type)
        val canRestrictPredicates = typeURIs.isNotEmpty() && typeInfo.fieldDefinitions.none {
            it.name == FIELD_PREDICATES_NAME
        }
        val predicateIRIs = mutableSetOf<String>().apply {
            if (canRestrictPredicates) add(RDFVocab.type)
        }
        val projections =
            typeInfo.fieldDefinitions.filterNot { it.name in setOf(FIELD_ID_NAME, FIELD_TYPES_NAME) }.map { field ->
                when (field.name) {
                    FIELD_PREDICATES_NAME -> "groupUniqArray(predicate) AS $FIELD_PREDICATES_NAME"
                    else -> {
                        val fqFieldName = getFQName(field, context)
                        if (canRestrictPredicates) {
                            predicateIRIs.add(fqFieldName)
                        }
                        if (!field.type.isNullable() || isMustExist(field)) {
                            // Register non-nullable fields to be used in the HAVING clause
                            nonNullFields.add(field.name)
                        }
                        // Look if there is a filter specified at the Slice schema level
                        val schemaFilter = field.getDirectiveArg<StringValue>(DIRECTIVE_FILTER_NAME, ARG_IF_NAME)?.value
                        buildProjection(fqFieldName, field.name, field.type.isList(), schemaFilter)
                    }
                }
            }
        val sourceExpr = candidateSubjectFilter(typeInfo.subjectConstraints)?.let { subjectFilter ->
            "(${rawCurrentStateExpr(predicateIRIs, subjectFilter = subjectFilter)})"
        } ?: run {
            collapsedStateExpr(typeURIs, predicateIRIs = predicateIRIs)
        }
        val subjectConstraintConditions = typeInfo.subjectConstraints
            .filterNot { constraint ->
                val comparison = constraint.filter as? ComparisonNode
                comparison?.selector == "object" &&
                    comparison.operator in setOf(RSQLOperators.EQUAL, RSQLOperators.IN)
            }
            .map { constraint ->
            val predicateIRI = getFQName(constraint.relationInfo.fieldDefinition, context)
            val constrainedSourceExpr = collapsedStateExpr(
                domainClassIRIs = typeURIs,
                predicateIRIs = listOf(predicateIRI)
            )
            val constrainedSubjectColumn = if (constraint.relationInfo.reverse) "object" else "subject"
            "subject IN (SELECT $constrainedSubjectColumn FROM $constrainedSourceExpr WHERE predicate = '$predicateIRI' AND ${
                ToSQLFilterVisitor(context).visitNode(constraint.filter)
            })"
        }
        val whereCondition = subjectConstraintConditions.takeIf { it.isNotEmpty() }
            ?.joinToString(" AND ", " WHERE ") ?: ""
        val havingConditions = typeURIs.takeIf { it.isNotEmpty() }
            ?.let { "hasAll(_types, ${arrayExpr(it)})" }
            .let { listOfNotNull(it) + nonNullFields.map { field -> "$field IS NOT NULL" } }
        val havingCondition = havingConditions.takeIf { it.isNotEmpty() }
            ?.joinToString(" AND ", " HAVING ") ?: ""
        return "${typeInfo.identifier} AS (SELECT ${(fixedProjections + projections).joinToString()} FROM $sourceExpr$whereCondition GROUP BY subject$havingCondition)"
    }

}

/**
 * Builder for CTEs representing a relation (field) between GraphQL types. Handles collapsing state and time travel,
 * and determines the appropriate way to fetch related objects or literal values based on the field definition.
 * Also supports reversed relations based on the presence of the @predicate directive with reverse: true.
 */
class RelationCTEBuilder(
    atChangeId: String?,
    env: DataFetchingEnvironment,
    val relationInfo: RelationInfo
) : AbstractCTEBuilder(relationInfo.context, atChangeId, env) {
    val isScalar = relationInfo.fieldDefinition.type.isScalar()
    val toType = relationInfo.fieldDefinition.type.takeIf { !isScalar }?.innerType<GraphQLCompositeType>()

    fun build(): String {
        // Check if the relation is reversed based on the presence of the @predicate directive with reverse: true
        // TODO: make reverse work when defined in context vs. in the graphql schema
        val reverse = relationInfo.fieldDefinition.getDirectiveArg<BooleanValue>(
            DIRECTIVE_PREDICATE_NAME,
            ARG_REVERSE_NAME
        )?.isValue ?: false
        return if (reverse) {
            // Scalars are not considered for reversed relations, unless they are IDs, as these can be used to point to another Resource in the KG.
            if ((isScalar || toType == null) && relationInfo.fieldDefinition.type.innerType<GraphQLNamedType>() != Scalars.GraphQLID) {
                throw IllegalArgumentException("Only relations pointing to another Resource can be reversed. Offending field: ${relationInfo.fieldDefinition.name} in type ${relationInfo.parentType.name}")
            }
            val predicateIRI = getFQName(relationInfo.fieldDefinition, context)
            val sourceExpr = collapsedStateExpr(
                domainClassIRIs = toType?.let { getTypeURIsToMatch(toType) } ?: emptyList(),
                rangeClassIRIs = getTypeURIsToMatch(relationInfo.parentType),
                predicateIRIs = listOf(predicateIRI)
            )
            "${relationInfo.identifier} AS (SELECT object as id, subject as value, '' as datatype FROM $sourceExpr WHERE predicate = '${
                predicateIRI
            }')"
        } else {
            val rangeClassIRIs =
                toType?.let { nonNullToType -> getTypeURIsToMatch(nonNullToType) } ?: emptyList()
            when {
                relationInfo.fieldDefinition.name == FIELD_RELATIONS_NAME -> {
                    // Relations are predicates pointing to another Resource (and not a Literal), so look for records where datatype is empty.
                    val sourceExpr = collapsedStateExpr(
                        domainClassIRIs = getTypeURIsToMatch(relationInfo.parentType),
                        rangeClassIRIs = rangeClassIRIs
                    )
                    "${relationInfo.identifier} AS (SELECT subject as id, object as $RELATIONS_PROJ_TARGET, predicate as value, datatype FROM $sourceExpr WHERE datatype = '')"
                }

                else -> {
                    val fqFieldName = getFQName(relationInfo.fieldDefinition, context)
                    val objectIRIs = objectFilterValues(relationInfo.relationFilter as? ComparisonNode)
                    val relationFilter = relationInfo.relationFilter
                        ?.let { " AND ${ToSQLFilterVisitor(context).visitNode(it)}" } ?: ""
                    val subjectFilter = candidateSubjectFilter(relationInfo.subjectConstraints)
                    val sourceExpr = if (objectIRIs.isNotEmpty() || subjectFilter != null) {
                        "(${rawCurrentStateExpr(listOf(fqFieldName), objectIRIs, subjectFilter)})"
                    } else {
                        collapsedStateExpr(
                            domainClassIRIs = getTypeURIsToMatch(relationInfo.parentType),
                            rangeClassIRIs = rangeClassIRIs,
                            predicateIRIs = listOf(fqFieldName)
                        )
                    }
                    "${relationInfo.identifier} AS (SELECT subject as id, object as value, datatype FROM $sourceExpr WHERE predicate = '$fqFieldName'$relationFilter)"
                }
            }
        }
    }

}
