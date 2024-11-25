package kvasir.plugins.kg.clickhouse.graphql

import com.google.common.hash.Hashing
import cz.jirutka.rsql.parser.RSQLParser
import cz.jirutka.rsql.parser.ast.AndNode
import cz.jirutka.rsql.parser.ast.ComparisonNode
import cz.jirutka.rsql.parser.ast.Node
import cz.jirutka.rsql.parser.ast.RSQLOperators
import graphql.language.*
import graphql.schema.*
import io.quarkus.cache.Cache
import io.quarkus.cache.CacheName
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import jakarta.enterprise.context.ApplicationScoped
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

@ApplicationScoped
class ConvertToSQLResolver(
    private val clickhouseClient: ClickhouseClient,
    @CacheName("total-count-request-cache")
    private val countCache: Cache
) {

    fun getDatafetcher(
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
                        env.field,
                        env.fieldDefinition,
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

                    if (env.fieldDefinition.name == "totalCount") {
                        val sqlConvertor = SQLConvertor(
                            context,
                            atTimestamp,
                            databaseName,
                            DATA_TABLE,
                            env.executionStepInfo.parent.field.singleField,
                            env.executionStepInfo.parent.fieldDefinition,
                            env,
                            SQLConvertorMode.COUNT
                        )
                        val (sql, columns) = sqlConvertor.toSQL()

                        val cacheKey = Hashing.farmHashFingerprint64().hashBytes(sql.toByteArray()).toString()
                        countCache.getAsync(cacheKey) { key ->
                            clickhouseClient.query(GenericQuerySpec(databaseName, DATA_TABLE, columns), sql)
                                .map { result ->
                                    result[0]["totalCount"]
                                }
                        }.convert().toCompletionStage()
                    } else {

                        val value = when (source) {
                            is Map<*, *> -> source["_$key"] ?: source[key]
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

}

private const val COLLAPSE_STATE_EXPR = "HAVING argMax(sign, timestamp) > 0"
private const val VIA_SPLIT_CHAR = "^^"

enum class SQLConvertorMode {
    GET_DATA,
    COUNT
}

class SQLConvertor(
    val context: Map<String, Any>,
    val atTimestamp: Instant?,
    database: String,
    table: String,
    private val targetField: Field,
    private val targetFieldDefinition: GraphQLFieldDefinition,
    private val env: DataFetchingEnvironment,
    private val mode: SQLConvertorMode = SQLConvertorMode.GET_DATA
) {

    private val tableRef = "$database.$table"

    fun toSQL(): SQLQuery {
        val outputType = GraphQLTypeUtil.unwrapAll(targetFieldDefinition.type) as GraphQLDirectiveContainer
        val limit = limitStatement(targetField)
        val orderBy = orderByStatement(targetField, "_")
        val idField = when (mode) {
            SQLConvertorMode.GET_DATA -> "_id"
            SQLConvertorMode.COUNT -> "subject"
        }
        val whereClause = listOfNotNull(
            typeFilter(listOf(getFQName(outputType))),
            getNodeFilter(targetField),
            getArgsFilter(targetField),
        ).takeIf { it.isNotEmpty() }?.let { if (it.size == 1) it.first() else AndNode(it) }
            ?.let {
                "WHERE ${
                    GraphQLFilterVisitor2(context).visitNode(
                        SelectorReplacingFilterVisitor(
                            "id",
                            idField
                        ).visitNode(it)
                    )
                }"
            } ?: ""
        val (nestedFields, enableCount) = getNestedFields(targetField, idField)
        val projection =
            (
                    listOf("subject AS $idField") + nestedFields.map {
                        val baseFieldProj = "arrayDistinct(ARRAY_AGG(${it.field.name}))"
                        (if (it.field.selectionSet != null) "arrayFilter(x -> notEmpty(x), $baseFieldProj)" else baseFieldProj)
                            .plus(" AS _${it.field.name}")
                    }
                    ).joinToString()
        return when (mode) {
            SQLConvertorMode.GET_DATA -> {
                SQLQuery(
                    "SELECT $projection FROM $tableRef ${
                        nestedFields.joinToString(" ") { it.joinStatement }
                    } $whereClause GROUP BY subject $orderBy $limit",
                    listOf(idField) + nestedFields.map { "_${it.field.name}" }
                )
            }

            SQLConvertorMode.COUNT -> {
                val modifiedWhere = getRelationshipFilter()?.let { extraFilter ->
                    if (whereClause.isNotEmpty()) {
                        "$whereClause AND $extraFilter"
                    } else {
                        "WHERE $extraFilter"
                    }
                } ?: whereClause
                SQLQuery(
                    "SELECT count(distinct subject) as totalCount FROM $tableRef ${
                        nestedFields.joinToString(" ") { it.joinStatement }
                    } $modifiedWhere ",
                    listOf("totalCount")
                )
            }
        }
    }

    fun scalarFieldJoinStatement(field: Field, parentJoinField: String): String {
        val name = field.name
        val joinField = "${name}_holder"
        val whereClause = listOfNotNull(
            "predicate = '${getFQName(field.name)}'",
            atTimestamp?.let { "timestamp <= '${ClickhouseUtils.convertInstant(it)}'" },
            context[JsonLdKeywords.language]?.let { "(datatype != '${RDFVocab.langString}' OR language = '$it')" }
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
        val (nestedFields, enableCount) = getNestedFields(field, "object")
        val limit = limitStatement(field).takeIf { it.isNotEmpty() }?.let { "$it BY subject" } ?: ""
        val orderBy = orderByStatement(field, "$name['", "']")
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
            (listOf(
                "'id'" to "object",
                "'via'" to "[concat(subject, '$VIA_SPLIT_CHAR', predicate)]"
            ) + nestedFields.map { "'${it.field.name}'" to "arrayDistinct(ARRAY_AGG(${it.field.name}))" })
                .joinToString { (a, b) -> "$a,$b" }
        return "${getJoinType(field)} (SELECT subject AS $joinField, map($mappedFields) as $name FROM $tableRef ${
            nestedFields.joinToString(" ") { it.joinStatement }
        } $whereClause GROUP BY ${
            SORT_COLUMNS.joinToString(
                prefix = "(",
                postfix = ")"
            )
        } $COLLAPSE_STATE_EXPR $orderBy $limit) ${name}_join ON $parentJoinField = $joinField"
    }

    private fun limitStatement(field: Field): String {
        return (field.arguments.find { it.name == "first" }?.value as? IntValue)?.value?.let { limit ->
            val skip = (field.arguments.find { it.name == "skip" }?.value as? IntValue)?.value ?: 0
            "LIMIT $limit OFFSET $skip"
        } ?: ""
    }

    private fun orderByStatement(field: Field, prefix: String = "", postFix: String = ""): String {
        return (field.arguments.find { it.name == "orderBy" }?.value as? ArrayValue)?.values?.let { values ->
            val fields = values.map { (it as StringValue).value }
            "ORDER BY ${fields.joinToString { prefix + (if (it.startsWith("-")) "${it.substring(1)} DESC" else it.toString()) + postFix }} "
        } ?: ""
    }

    private fun getJoinType(field: Field): String =
        if (field.hasDirective(AbstractKnowledgeGraph.optionalDirective.name)) "LEFT JOIN" else "JOIN"

    private fun getNestedFields(field: Field, parentJoinField: String): FieldInfo {
        val processedFields = field.selectionSet.selections.flatMap { selection ->
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
        }
        return FieldInfo(processedFields.filterNot { it.field.name == "id" || it.field.name == "totalCount" }
            .map { (nestedField, typeFilter) ->
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
            }, processedFields.any { it.field.name == "totalCount" })
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

    private fun getRelationshipFilter(): String? {
        val source = env.getSource<Any?>()
        val via: List<String>? = when (source) {
            is Map<*, *> -> source["via"] as List<String>?
            is JsonObject -> source.getValue("via") as List<String>?
            else -> null
        }
        return via?.let { path ->
            val (parentId, predicate) = path.first().split(VIA_SPLIT_CHAR)
            "subject IN (SELECT object FROM $tableRef WHERE subject = '$parentId' AND predicate = '$predicate')"
        }
    }

}

data class FieldInfo(val fieldSelection: List<SelectedField>, val enableCount: Boolean)

data class SQLQuery(val sql: String, val columns: List<String>)

data class FieldToJoin(val field: Field, val typeFilter: Node?)
data class SelectedField(val field: Field, val joinStatement: String)