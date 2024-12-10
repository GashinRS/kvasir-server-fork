package kvasir.plugins.kg.clickhouse.graphql

import com.google.common.hash.Hashing
import cz.jirutka.rsql.parser.RSQLParser
import cz.jirutka.rsql.parser.ast.AndNode
import cz.jirutka.rsql.parser.ast.ComparisonNode
import cz.jirutka.rsql.parser.ast.Node
import cz.jirutka.rsql.parser.ast.RSQLOperators
import graphql.ExecutionResult
import graphql.execution.ExecutionStepInfo
import graphql.execution.FetchedValue
import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimpleInstrumentationContext
import graphql.execution.instrumentation.SimplePerformantInstrumentation
import graphql.execution.instrumentation.parameters.InstrumentationCreateStateParameters
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldCompleteParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.language.*
import graphql.schema.*
import io.smallrye.mutiny.Multi
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.rdf.*
import kvasir.plugins.kg.clickhouse.OffsetBasedCursor
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.databaseFromPodId
import kvasir.plugins.kg.clickhouse.specs.DATA_TABLE
import kvasir.plugins.kg.clickhouse.specs.GenericQuerySpec
import kvasir.plugins.kg.clickhouse.specs.SORT_COLUMNS
import kvasir.plugins.kg.clickhouse.utils.ClickhouseUtils
import kvasir.utils.graphql.isList
import kvasir.utils.kg.AbstractKnowledgeGraph
import java.time.Instant
import java.util.concurrent.CompletableFuture

@ApplicationScoped
class ConvertToSQLResolver(
    private val clickhouseClient: ClickhouseClient
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
                    val value = env.getFromSource<Any>(env.fieldDefinition.name)
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
    private val targetGraphFilterNode = getTargetGraphs()?.let { targetGraphs ->
        val node = ComparisonNode(
            RSQLOperators.IN,
            "graph",
            targetGraphs
        )
        node
    }

    fun toSQL(): SQLQuery {
        val outputType = GraphQLTypeUtil.unwrapAll(targetFieldDefinition.type) as GraphQLDirectiveContainer
        val (pageSize, offset) = targetField.getPaginationInfo()
        val orderBy = orderByStatement(targetField, "_")
        val idField = when (mode) {
            SQLConvertorMode.GET_DATA -> "_id"
            SQLConvertorMode.COUNT -> "subject"
        }
        val whereClause = listOfNotNull(
            targetGraphFilterNode,
            getFQName(outputType, context).takeIf { it != RDFSVocab.Resource }?.let { typeFilter(listOf(it)) },
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
        val (nestedFields) = getNestedFields(
            targetField,
            GraphQLTypeUtil.unwrapAll(targetFieldDefinition.type) as GraphQLFieldsContainer,
            idField
        )
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
                    } $whereClause GROUP BY subject $orderBy LIMIT $offset, $pageSize",
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

    fun scalarFieldJoinStatement(
        field: Field,
        fieldDefinition: GraphQLFieldDefinition,
        parentJoinField: String
    ): String {
        val name = field.name
        val (pageSize, offset) = field.getPaginationInfo()
        val joinField = "${name}_holder"
        val whereClause = listOfNotNull(
            targetGraphFilterNode?.let { GraphQLFilterVisitor2(context).visitNode(it) },
            "predicate = '${getFQName(fieldDefinition, context)}'",
            atTimestamp?.let { "timestamp <= '${ClickhouseUtils.convertInstant(it)}'" },
            context[JsonLdKeywords.language]?.let { "(datatype != '${RDFVocab.langString}' OR language = '$it')" }
        ).takeIf { it.isNotEmpty() }?.joinToString(" AND ", "WHERE ") ?: ""
        return "${getJoinType(field)} (SELECT subject AS $joinField, object AS $name FROM $tableRef $whereClause GROUP BY ${
            SORT_COLUMNS.joinToString(
                prefix = "(",
                postfix = ")"
            )
        } $COLLAPSE_STATE_EXPR LIMIT $offset, $pageSize BY subject) ${name}_join ON $parentJoinField = $joinField"
    }

    fun relationFieldJoinStatement(
        field: Field,
        fieldDefinition: GraphQLFieldDefinition,
        parentJoinField: String
    ): String {
        val name = field.name
        val (pageSize, offset) = field.getPaginationInfo()
        val joinField = "${name}_holder"
        val (nestedFields) = getNestedFields(
            field,
            GraphQLTypeUtil.unwrapAll(fieldDefinition.type) as GraphQLFieldsContainer,
            "object"
        )
        val orderBy = orderByStatement(field, "$name['", "']")
        val whereClause = listOfNotNull(
            targetGraphFilterNode?.let { GraphQLFilterVisitor2(context).visitNode(it) },
            "predicate = '${getFQName(fieldDefinition, context)}'",
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
                "'id'" to "object"
            ) + nestedFields.map { "'${it.field.name}'" to "arrayDistinct(ARRAY_AGG(${it.field.name}))" })
                .joinToString { (a, b) -> "$a,$b" }
        return "${getJoinType(field)} (SELECT subject AS $joinField, map($mappedFields) as $name FROM $tableRef ${
            nestedFields.joinToString(" ") { it.joinStatement }
        } $whereClause GROUP BY ${
            SORT_COLUMNS.joinToString(
                prefix = "(",
                postfix = ")"
            )
        } $COLLAPSE_STATE_EXPR $orderBy LIMIT $offset, $pageSize BY subject) ${name}_join ON $parentJoinField = $joinField"
    }

    private fun getTargetGraphs(): List<String>? {
        val graphDirective = env.document.getDefinitionsOfType(OperationDefinition::class.java)
            .first { it.operation == OperationDefinition.Operation.QUERY }.directivesByName["graph"]?.firstOrNull()
        return graphDirective?.let { directive ->
            directive.getArgument("iri")?.value?.let { value ->
                when (value) {
                    is StringValue -> listOf(value.value)
                    is ArrayValue -> value.values.mapNotNull { (it as? StringValue)?.value }
                    else -> null
                }?.takeIf { it.isNotEmpty() }
            }
        }
    }

    private fun orderByStatement(field: Field, prefix: String = "", postFix: String = ""): String {
        return (field.arguments.find { it.name == "orderBy" }?.value as? ArrayValue)?.values?.let { values ->
            val fields = values.map { (it as StringValue).value }
            "ORDER BY ${fields.joinToString { prefix + (if (it.startsWith("-")) "${it.substring(1)} DESC" else it.toString()) + postFix }} "
        } ?: ""
    }

    private fun getJoinType(field: Field): String =
        if (field.hasDirective(AbstractKnowledgeGraph.optionalDirective.name)) "LEFT JOIN" else "JOIN"

    private fun getNestedFields(
        field: Field,
        outputDefinition: GraphQLFieldsContainer,
        parentJoinField: String
    ): FieldInfo {
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
                    //... and treat included selection set as fields, but with an additional type condition
                    val requiredType = getFQName(fragmentDefinition.typeCondition.name)
                    fragmentDefinition.selectionSet.selections.filterIsInstance<Field>()
                        .map { FieldToJoin(it, typeFilter(listOf(requiredType))) }
                }

                is Field -> listOf(FieldToJoin(selection, null))
                else -> emptyList()
            }
        }
        return FieldInfo(processedFields.filterNot { it.field.name == "id" || it.field.name.startsWith("__") }
            .map { (nestedField, typeFilter) ->
                // TODO: handle typeFilters
                SelectedField(
                    nestedField, if (nestedField.selectionSet == null) {
                        // Scalar field
                        scalarFieldJoinStatement(
                            nestedField,
                            outputDefinition.getFieldDefinition(nestedField.name),
                            parentJoinField
                        )
                    } else {
                        // Relation field
                        relationFieldJoinStatement(
                            nestedField,
                            outputDefinition.getFieldDefinition(nestedField.name),
                            parentJoinField
                        )
                    }
                )
            })
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
        val targetSubject = env.getFromSource<Any>("id")
        val targetPredicate = getFQName(targetFieldDefinition, context)
        return targetSubject?.let { "subject IN (SELECT object FROM $tableRef WHERE subject = '$targetSubject' AND predicate = '$targetPredicate')" }
    }

}

class PaginationInstrumentationState(
    val state: MutableMap<String, String> = mutableMapOf(),
    val environments: MutableMap<String, DataFetchingEnvironment> = mutableMapOf()
) : InstrumentationState {

    fun addCountTarget(executionStepInfo: ExecutionStepInfo, countSql: String) {
        state[executionStepInfo.path.toString()] = countSql
    }

}

class PaginationInstrumentation(
    val clickhouseClient: ClickhouseClient,
    val podId: String,
    val context: Map<String, Any>,
    val atTimestamp: Instant?
) : SimplePerformantInstrumentation() {

    companion object {
        const val EXTENSION_ID = "pagination"
    }

    private val databaseName = databaseFromPodId(podId)

    override fun createState(parameters: InstrumentationCreateStateParameters?): InstrumentationState? {
        return PaginationInstrumentationState()
    }

    override fun beginFieldFetch(
        parameters: InstrumentationFieldFetchParameters,
        state: InstrumentationState
    ): InstrumentationContext<in Any> {
        state as PaginationInstrumentationState
        state.environments[parameters.executionStepInfo.path.toString()] = parameters.environment
        return SimpleInstrumentationContext.noOp()
    }

    override fun beginFieldCompletion(
        parameters: InstrumentationFieldCompleteParameters,
        state: InstrumentationState
    ): InstrumentationContext<in Any> {
        state as PaginationInstrumentationState
        if (parameters.executionStepInfo.fieldDefinition.type.isList()) {
            val env = state.environments[parameters.executionStepInfo.path.toString()]!!
            val (pageSize, _) = env.field.getPaginationInfo()
            val outputSize = ((parameters.fetchedValue as? FetchedValue)?.fetchedValue as? List<*>)?.size
            if (outputSize != null && outputSize == pageSize) {
                return object : SimpleInstrumentationContext<Any>() {
                    override fun onCompleted(result: Any?, t: Throwable?) {
                        val sqlConvertor = SQLConvertor(
                            context,
                            atTimestamp,
                            databaseName,
                            DATA_TABLE,
                            env.field,
                            env.fieldDefinition,
                            env,
                            SQLConvertorMode.COUNT
                        )
                        val (sql, _) = sqlConvertor.toSQL()
                        state.addCountTarget(parameters.executionStepInfo, sql)
                    }
                }
            }
        }
        return SimpleInstrumentationContext.noOp()
    }

    override fun instrumentExecutionResult(
        executionResult: ExecutionResult,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState
    ): CompletableFuture<ExecutionResult> {
        return Multi.createFrom().iterable((state as PaginationInstrumentationState).state.entries)
            .onItem().transformToUni { (path, sql) ->
                val env = state.environments[path]!!
                val (pageSize, offset) = env.field.getPaginationInfo()
                clickhouseClient.query(GenericQuerySpec(databaseName, DATA_TABLE, listOf("totalCount")), sql)
                    .map { result ->
                        val totalCount = result[0]["totalCount"].toString().toLong()
                        mapOf(
                            JsonLdKeywords.id to "kvasir:qr-page-info:${
                                Hashing.farmHashFingerprint64()
                                    .hashString(path, Charsets.UTF_8)
                            }",
                            "path" to path,
                            "parent" to env.getFromSource("id"),
                            (if (env.executionStepInfo.path.parent.isRootPath) "class" else "predicate") to getFQName(
                                env.fieldDefinition,
                                context
                            ),
                            "totalCount" to totalCount,
                            "next" to if (offset + pageSize < totalCount) OffsetBasedCursor(offset + pageSize).encode() else null,
                            "previous" to if (offset - pageSize >= 0) OffsetBasedCursor(offset - pageSize).encode() else null
                        ).filterValues { it != null }
                    }
            }
            .merge().collect().asList()
            .map { pageData ->
                executionResult.transform { result ->
                    result.extensions(mapOf(EXTENSION_ID to pageData))
                }
            }
            .convert().toCompletableFuture()
    }

}

data class FieldInfo(val fieldSelection: List<SelectedField>)

data class SQLQuery(val sql: String, val columns: List<String>)

data class FieldToJoin(val field: Field, val typeFilter: Node?)
data class SelectedField(val field: Field, val joinStatement: String)

private fun Field.getPaginationInfo(): Pair<Int, Long> {
    val pageSize = (arguments.find { it.name == "pageSize" }?.value as? IntValue)?.value?.toInt()
        ?: AbstractKnowledgeGraph.DEFAULT_PAGE_SIZE
    val cursor = (arguments.find { it.name == "cursor" }?.value as? StringValue)?.value?.let {
        OffsetBasedCursor.fromString(it)?.offset
    } ?: 0L
    return pageSize to cursor
}

private fun <T> DataFetchingEnvironment.getFromSource(key: String): T? {
    val source = getSource<Any?>()
    return when (source) {
        is Map<*, *> -> source["_$key"] ?: source[key]
        is JsonObject -> source.getValue(key)
        else -> null
    } as T?
}

private fun getFQName(node: GraphQLDirectiveContainer, context: Map<String, Any>): String {
    return JsonLdHelper.getFQName(node.name, context, "_")?.takeIf { it != node.name }
        ?: run {
            node.getAppliedDirective("predicate")?.getArgument("iri")?.getValue<String>()
                ?: node.getAppliedDirective("type")?.getArgument("iri")?.getValue<String>()

        } ?: throw IllegalArgumentException("No semantic context found for ${node.name}")
}