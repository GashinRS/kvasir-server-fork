package kvasir.plugins.kg.clickhouse.graphql.resolver

import cz.jirutka.rsql.parser.RSQLParser
import graphql.Scalars
import graphql.language.BooleanValue
import graphql.language.StringValue
import graphql.scalars.ExtendedScalars
import graphql.schema.*
import kvasir.definitions.kg.graphql.*
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.RDFVocab
import kvasir.plugins.kg.clickhouse.graphql.SELF_REF_SELECTOR
import kvasir.plugins.kg.clickhouse.graphql.SelectorReplacingFilterVisitor
import kvasir.plugins.kg.clickhouse.graphql.ToSQLFilterVisitor
import kvasir.plugins.kg.clickhouse.specs.COLLAPSED_STATE_BY_TYPE_TABLE
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
        val projections =
            typeInfo.fieldDefinitions.filterNot { it.name in setOf(FIELD_ID_NAME, FIELD_TYPES_NAME) }.map { field ->
                when (field.name) {
                    FIELD_PREDICATES_NAME -> "groupUniqArray(predicate) AS $FIELD_PREDICATES_NAME"
                    else -> {
                        val fqFieldName = getFQName(field, context)
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
        val sourceExpr =
            "$COLLAPSED_STATE_BY_TYPE_TABLE(domainClassIRIs=[${getTypeURIsToMatch(typeInfo.type).joinToString { "'$it'" }}],rangeClassIRIs=[],at_change_id=$atChangeIdExpr)"
        val havingCondition =
            nonNullFields.takeIf { it.isNotEmpty() }?.joinToString(" AND ", " HAVING ") { "$it IS NOT NULL" } ?: ""
        return "${typeInfo.identifier} AS (SELECT ${(fixedProjections + projections).joinToString()} FROM $sourceExpr GROUP BY subject$havingCondition)"
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
            val sourceExpr =
                "$COLLAPSED_STATE_BY_TYPE_TABLE(domainClassIRIs=[${toType?.let { getTypeURIsToMatch(toType).joinToString { "'$it'" } } ?: ""}],rangeClassIRIs=[${
                    getTypeURIsToMatch(relationInfo.parentType).joinToString { "'$it'" }
                }],at_change_id=$atChangeIdExpr)"
            "${relationInfo.identifier} AS (SELECT object as id, subject as value, '' as datatype FROM $sourceExpr WHERE predicate = '${
                getFQName(
                    relationInfo.fieldDefinition,
                    context
                )
            }')"
        } else {
            val rangeClassIRIs =
                toType?.let { nonNullToType -> getTypeURIsToMatch(nonNullToType).joinToString { "'$it'" } } ?: ""
            val sourceExpr =
                "$COLLAPSED_STATE_BY_TYPE_TABLE(domainClassIRIs=[${getTypeURIsToMatch(relationInfo.parentType).joinToString { "'$it'" }}],rangeClassIRIs=[$rangeClassIRIs],at_change_id=$atChangeIdExpr)"
            when {
                relationInfo.fieldDefinition.name == FIELD_RELATIONS_NAME -> {
                    // Relations are predicates pointing to another Resource (and not a Literal), so look for records where datatype is empty.
                    "${relationInfo.identifier} AS (SELECT subject as id, object as $RELATIONS_PROJ_TARGET, predicate as value, datatype FROM $sourceExpr WHERE datatype = '')"
                }

                else -> {
                    val fqFieldName = getFQName(relationInfo.fieldDefinition, context)
                    "${relationInfo.identifier} AS (SELECT subject as id, object as value, datatype FROM $sourceExpr WHERE predicate = '$fqFieldName')"
                }
            }
        }
    }

}