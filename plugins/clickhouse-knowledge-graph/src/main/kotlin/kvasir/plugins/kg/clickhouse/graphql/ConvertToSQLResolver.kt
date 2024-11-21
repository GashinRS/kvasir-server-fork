package kvasir.plugins.kg.clickhouse.graphql

import cz.jirutka.rsql.parser.RSQLParser
import cz.jirutka.rsql.parser.ast.AndNode
import cz.jirutka.rsql.parser.ast.ComparisonNode
import cz.jirutka.rsql.parser.ast.Node
import cz.jirutka.rsql.parser.ast.RSQLOperators
import graphql.language.*
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLDirectiveContainer
import graphql.schema.GraphQLTypeUtil
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.RDFVocab
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.databaseFromPodId
import kvasir.plugins.kg.clickhouse.specs.DATA_TABLE
import kvasir.plugins.kg.clickhouse.specs.GenericQuerySpec
import kvasir.plugins.kg.clickhouse.specs.SORT_COLUMNS
import kvasir.plugins.kg.clickhouse.utils.ClickhouseUtils
import kvasir.utils.kg.AbstractKnowledgeGraph
import java.time.Instant

object ConvertToSQLResolver {

    fun getDatafetcher(
        clickhouseClient: ClickhouseClient,
        podId: String,
        context: Map<String, Any>,
        atTimestamp: Instant?
    ): DataFetcher<Any> {
        return object : DataFetcher<Any> {
            override fun get(env: DataFetchingEnvironment): Any? {
                val databaseName = databaseFromPodId(podId)
                return if (env.executionStepInfo.path.parent.isRootPath) {
                    // Handle entrypoints
                    val sqlConvertor = SQLConvertor(
                        context,
                        atTimestamp,
                        databaseName,
                        DATA_TABLE,
                        env
                    )
                    val (sql, columns) = sqlConvertor.toSQL()
                    clickhouseClient.query(GenericQuerySpec(databaseName, DATA_TABLE, columns), sql)
                        .map { result ->
                            result
                        }
                        .convert().toCompletionStage()
                } else {
                    val source = env.getSource<Any?>()
                    val key = env.fieldDefinition.name
                    val value = when (source) {
                        is Map<*, *> -> source["_$key"]?:source[key]
                        is JsonObject -> source.getValue(key)
                        else -> null
                    }
                    when (value) {
                        is List<*> -> value.filterNotNull()
                        is JsonArray -> value.list.filterNotNull()
                        else -> value
                    }
                }
            }
        }
    }

}

private const val COLLAPSE_STATE_EXPR = "HAVING argMax(sign, timestamp) > 0"

class SQLConvertor(
    val context: Map<String, Any>,
    val atTimestamp: Instant?,
    database: String,
    table: String,
    private val env: DataFetchingEnvironment
) {

    private val tableRef = "$database.$table"

    fun toSQL(): SQLQuery {
        val outputType = GraphQLTypeUtil.unwrapAll(env.fieldDefinition.type) as GraphQLDirectiveContainer
        val whereClause = listOfNotNull(
            typeFilter(listOf(getFQName(outputType))),
            getNodeFilter(env.field),
            getArgsFilter(env.field)
        ).takeIf { it.isNotEmpty() }?.let { if (it.size == 1) it.first() else AndNode(it) }
            ?.let {
                "WHERE ${
                    GraphQLFilterVisitor2(context).visitNode(
                        SelectorReplacingFilterVisitor(
                            "id",
                            "_id"
                        ).visitNode(it)
                    )
                }"
            } ?: ""
        val nestedFields = getNestedFields(env.field, "_id")
        val projection =
            (
                    listOf("subject AS _id") + nestedFields.map {
                        val baseFieldProj = "arrayDistinct(ARRAY_AGG(${it.field.name}))"
                        (if (it.field.selectionSet != null) "arrayFilter(x -> notEmpty(x), $baseFieldProj)" else baseFieldProj)
                            .plus(" AS _${it.field.name}")
                    }
                    ).joinToString()
        return SQLQuery(
            "SELECT $projection FROM $tableRef ${
                nestedFields.joinToString(" ") { it.joinStatement }
            } $whereClause GROUP BY subject", listOf("_id") + nestedFields.map { "_${it.field.name}" }
        )
    }

    fun scalarFieldJoinStatement(field: Field, parentJoinField: String): String {
        val name = field.name
        val joinField = "${name}_holder"
        val whereClause = listOfNotNull(
            "predicate = '${getFQName(field.name)}'",
            atTimestamp?.let { "timestamp <= '${ClickhouseUtils.convertInstant(it)}'" },
            context[JsonLdKeywords.language]?.let{ "(datatype != '${RDFVocab.langString}' OR language = '$it')" }
        ).takeIf { it.isNotEmpty() }?.joinToString(" AND ", "WHERE ") ?: ""
        return "${getJoinType(field)} (SELECT subject AS $joinField, object AS $name FROM $tableRef $whereClause GROUP BY ${
            SORT_COLUMNS.joinToString(
                prefix = "(",
                postfix = ")"
            )
        } $COLLAPSE_STATE_EXPR) ${name}_join ON $parentJoinField = $joinField"
    }

    fun relationFieldJoinStatement(field: Field, parentJoinField: String): String {
        val name = field.name
        val joinField = "${name}_holder"
        val nestedFields = getNestedFields(field, "object")
        val whereClause = listOfNotNull(
            "predicate = '${getFQName(field.name)}'",
            atTimestamp?.let { "timestamp <= '${ClickhouseUtils.convertInstant(it)}'" },
            getNodeFilter(field)?.let { GraphQLFilterVisitor2(context).visitNode(it) },
            getArgsFilter(field)?.let {
                GraphQLFilterVisitor2(context).visitNode(
                    SelectorReplacingFilterVisitor(
                        "id",
                        "object"
                    ).visitNode(it)
                )
            }
        ).takeIf { it.isNotEmpty() }?.joinToString(" AND ", "WHERE ") ?: ""
        val mappedFields =
            (listOf("'id'" to "object") + nestedFields.map { "'${it.field.name}'" to "arrayDistinct(ARRAY_AGG(${it.field.name}))" })
                .joinToString { (a, b) -> "$a,$b" }
        return "${getJoinType(field)} (SELECT subject AS $joinField, map($mappedFields) as $name FROM $tableRef ${
            nestedFields.joinToString(" ") { it.joinStatement }
        } $whereClause GROUP BY ${
            SORT_COLUMNS.joinToString(
                prefix = "(",
                postfix = ")"
            )
        } $COLLAPSE_STATE_EXPR) ${name}_join ON $parentJoinField = $joinField"
    }

    private fun getJoinType(field: Field): String =
        if (field.hasDirective(AbstractKnowledgeGraph.optionalDirective.name)) "LEFT JOIN" else "JOIN"

    private fun getNestedFields(field: Field, parentJoinField: String): List<SelectedField> {
        return field.selectionSet.selections.flatMap { selection ->
            when (selection) {
                is InlineFragment -> {
                    val requiredType = getFQName(selection.typeCondition.name)
                    selection.selectionSet.selections.filterIsInstance<Field>()
                        .map { FieldToJoin(it, typeFilter(listOf(requiredType))) }
                }

                is FragmentSpread -> {
                    // Fragment spread, lookup FragmentDefinition...
                    val fragmentDefinition = env.fragmentsByName[selection.name]
                        ?: throw IllegalArgumentException("Fragment definition for '${selection.name}' not found")
                    //... and treat included selection set as fields, but with an additional type condition )
                    val requiredType = getFQName(fragmentDefinition.typeCondition.name)
                    fragmentDefinition.selectionSet.selections.filterIsInstance<Field>()
                        .map { FieldToJoin(it, typeFilter(listOf(requiredType))) }
                }

                is Field -> listOf(FieldToJoin(selection, null))
                else -> emptyList()
            }
        }.filterNot { it.field.name == "id" }.map { (nestedField, typeFilter) ->
            // TODO: handle typeFilters
            SelectedField(
                nestedField, if (nestedField.selectionSet == null) {
                    // Scalar field
                    scalarFieldJoinStatement(nestedField, parentJoinField)
                } else {
                    // Relation field
                    relationFieldJoinStatement(nestedField, parentJoinField)
                }
            )
        }
    }

    // TODO: rewrite this quick and dirty implementation
    private fun getArgsFilter(field: Field): Node? {
        val argFilters =
            field.arguments.filter { it.name !in AbstractKnowledgeGraph.defaultRelationArguments.map { it.name } || it.name == "id" }
                .map { argument ->
                    when (argument.value) {
                        is ArrayValue -> ComparisonNode(
                            RSQLOperators.IN,
                            argument.name,
                            (argument.value as ArrayValue).values.flatMap {
                                if (it is VariableReference) {
                                    val value = env.variables[it.name]!!
                                    if (value is List<*>) {
                                        value.map { it.toString() }
                                    } else {
                                        listOf(value.toString())
                                    }
                                } else {
                                    listOf(unboxScalar(it as ScalarValue<*>))
                                }
                            })

                        is VariableReference -> {
                            val value = env.variables[(argument.value as VariableReference).name]!!
                            if (value is List<*>) {
                                ComparisonNode(
                                    RSQLOperators.IN,
                                    argument.name,
                                    value.map { it.toString() }
                                )
                            } else {
                                ComparisonNode(
                                    RSQLOperators.EQUAL,
                                    argument.name,
                                    listOf(value.toString())
                                )
                            }
                        }

                        else -> ComparisonNode(
                            RSQLOperators.EQUAL,
                            argument.name,
                            listOf(unboxScalar(argument.value as ScalarValue<*>))
                        )
                    }
                }
        return argFilters.takeIf { it.isNotEmpty() }?.let {
            if (it.size == 1) it.first() else AndNode(it)
        }
    }


    private fun unboxScalar(scalar: ScalarValue<*>): String {
        return when (scalar) {
            is StringValue -> scalar.value.toString()
            is BooleanValue -> scalar.isValue.toString()
            is FloatValue -> scalar.value.toString()
            else -> throw IllegalArgumentException("Scalar type '${scalar::class.simpleName}' is not supported as argument")
        }
    }

    private fun getNodeFilter(field: Field): Node? {
        val subFields = field.selectionSet?.selections?.flatMap {
            if (it is InlineFragment) it.selectionSet.selections else listOf(it)
        }?.filterIsInstance<Field>()?.filterNot { it.name == "id" }
        val filters = subFields?.mapNotNull { subField ->
            subField.directives.firstOrNull { it.name == AbstractKnowledgeGraph.filterDirective.name }
                ?.let { directive ->
                    val rsqlExpr = directive.getArgument("if")?.value?.let { (it as StringValue).value }
                        ?: throw IllegalArgumentException("Missing 'if' argument containing RSQL expression on filter directive")
                    val rsqlParser = RSQLParser()
                    rsqlParser.parse(rsqlExpr).accept(SelectorReplacingFilterVisitor(SELF_REF_SELECTOR, subField.name))
                }
        }
        return filters?.takeIf { it.isNotEmpty() }?.let {
            if (it.size == 1) it.first() else AndNode(it)
        }
    }

    private fun handleFieldMultiplicity(values: List<Any>): Any? {
        return if (GraphQLTypeUtil.isList(env.fieldDefinition.type) || GraphQLTypeUtil.isList(
                GraphQLTypeUtil.unwrapOne(
                    env.fieldDefinition.type
                )
            )
        ) {
            values
        } else {
            values.firstOrNull()
        }
    }

    private fun getFQName(node: GraphQLDirectiveContainer): String {
        return JsonLdHelper.getFQName(node.name, context, "_")?.takeIf { it != node.name }
            ?: run {
                node.getAppliedDirective("predicate")?.getArgument("iri")?.getValue<String>()
                    ?: node.getAppliedDirective("type")?.getArgument("iri")?.getValue<String>()

            } ?: throw IllegalArgumentException("No semantic context found for ${node.name}")
    }

    private fun getFQName(name: String): String {
        return JsonLdHelper.getFQName(name, context, "_")?.takeIf { it != name }
            ?: throw IllegalArgumentException("No semantic context found for $name")
    }

    private fun typeFilter(requiredTypes: List<String>): Node {
        return AndNode(
            listOf(
                ComparisonNode(RSQLOperators.EQUAL, "predicate", listOf(RDFVocab.type)),
                ComparisonNode(RSQLOperators.IN, "object", requiredTypes)
            )
        )
    }

}

data class SQLQuery(val sql: String, val columns: List<String>)

data class FieldToJoin(val field: Field, val typeFilter: Node?)
data class SelectedField(val field: Field, val joinStatement: String)