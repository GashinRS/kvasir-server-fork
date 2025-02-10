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
import kvasir.baseimpl.kg.DefaultKnowledgeGraph
import kvasir.baseimpl.kg.SchemaGenerator
import kvasir.definitions.kg.graphql.*
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.RDFSVocab
import kvasir.definitions.rdf.RDFVocab
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.specs.DATA_TABLE
import kvasir.plugins.kg.clickhouse.specs.GenericQuerySpec
import kvasir.plugins.kg.clickhouse.specs.REVERSED_SORT_COLUMNS
import kvasir.plugins.kg.clickhouse.specs.SORT_COLUMNS
import kvasir.plugins.kg.clickhouse.utils.ClickhouseUtils
import kvasir.plugins.kg.clickhouse.utils.databaseFromPodId
import kvasir.utils.cursors.OffsetBasedCursor
import kvasir.utils.graphql.*
import java.time.Instant
import java.util.concurrent.CompletableFuture

@ApplicationScoped
class ConvertToSQLResolver(
    private val clickhouseClient: ClickhouseClient
) : DatafetcherProvider {

    override fun getDatafetcher(
        podId: String,
        context: Map<String, Any>,
        atTimestamp: Instant?
    ): DataFetcher<Any> {
        return DataFetcher<Any> { env ->
            val databaseName = databaseFromPodId(podId)
            if (env.executionStepInfo.path.parent.isRootPath) {
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
                    .convert().toCompletionStage()
            } else {
                val value = env.getFromSource<Any>(env.fieldDefinition.name)

                // When the query expects a complex object, but only an id is provided (e.g. by a different storage backend)...
                if (!isResultComplete(env, value)) {
                    //... then the object should be loaded using a query
                    // TODO: optimize using data loaders
                    val sqlConvertor = SQLConvertor(
                        context,
                        atTimestamp,
                        databaseName,
                        DATA_TABLE,
                        env.field,
                        env.fieldDefinition,
                        env,
                        mode = SQLConvertorMode.GET_DATA,
                        (if (value is Iterable<*>) value else listOf(value)).map {
                            it as Map<String, Any>
                            (it["_id"] ?: it["id"]) as String
                        }
                    )
                    val (sql, columns) = sqlConvertor.toSQL()
                    clickhouseClient.query(GenericQuerySpec(databaseName, DATA_TABLE, columns), sql)
                        .convert().toCompletionStage()
                } else {
                    // Else return the provided values (filtering nulls from collections)
                    val returnList = env.fieldDefinition.type.isList()
                    when {
                        value is Iterable<*> && returnList -> value.filterNotNull()
                        value is Iterable<*> -> value.firstOrNull()
                        returnList -> listOf(value)
                        else -> value
                    }
                }
            }
        }
    }

    private fun isResultComplete(env: DataFetchingEnvironment, value: Any?): Boolean {
        return if (env.field.selectionSet != null) {
            (if (value is Iterable<*>) value else listOf(value)).filterNotNull().all {
                val valueMap = when (it) {
                    is Map<*, *> -> it
                    is JsonObject -> it.map
                    else -> emptyMap()
                }
                // Check if all attributes are accounted
                val selectedFields = env.field.selectionSet.selections.filterIsInstance<Field>().map { f -> f.name }
                valueMap.keys.containsAll(selectedFields)
            }
        } else {
            true
        }
    }

}

private const val COLLAPSE_STATE_EXPR = "HAVING argMax(sign, timestamp) > 0"

enum class SQLConvertorMode {
    GET_DATA,
    COUNT
}

open class SQLConvertor(
    val context: Map<String, Any>,
    val atTimestamp: Instant?,
    protected val database: String,
    table: String,
    protected val targetField: Field,
    protected val targetFieldDefinition: GraphQLFieldDefinition,
    protected val env: DataFetchingEnvironment,
    protected val mode: SQLConvertorMode = SQLConvertorMode.GET_DATA,
    protected val subjectSelectors: List<String>? = null
) {

    protected val tableRef = "$database.$table"
    protected val targetGraphFilterNode = getTargetGraphs()?.let { targetGraphs ->
        val node = ComparisonNode(
            RSQLOperators.IN,
            "graph",
            targetGraphs
        )
        node
    }

    open fun toSQL(): SQLQuery {
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
            getNodeFilter(targetField, targetFieldDefinition),
            getArgsFilter(targetField),
            subjectSelectors?.let { ComparisonNode(RSQLOperators.IN, "id", it) }
        ).takeIf { it.isNotEmpty() }?.let { if (it.size == 1) it.first() else AndNode(it) }
            ?.let {
                "WHERE ${
                    GraphQLFilterVisitor(context).visitNode(
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
            targetGraphFilterNode?.let { GraphQLFilterVisitor(context).visitNode(it) },
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
        val reverse = fieldDefinition.getAppliedDirective(DIRECTIVE_PREDICATE_NAME).getArgument(ARG_REVERSE_NAME)
            .getValue<Boolean>()
        val (pageSize, offset) = field.getPaginationInfo()
        val joinFieldName = "${name}_holder"
        val (nestedFields) = getNestedFields(
            field,
            GraphQLTypeUtil.unwrapAll(fieldDefinition.type) as GraphQLFieldsContainer,
            if (reverse) "subject" else "object"
        )
        val orderBy = orderByStatement(field, "$name['", "']")
        val whereClause = listOfNotNull(
            targetGraphFilterNode?.let { GraphQLFilterVisitor(context).visitNode(it) },
            "predicate = '${getFQName(fieldDefinition, context)}'",
            atTimestamp?.let { "timestamp <= '${ClickhouseUtils.convertInstant(it)}'" },
            getNodeFilter(field, fieldDefinition)?.let { GraphQLFilterVisitor(context).visitNode(it) },
            getArgsFilter(field)?.let {
                GraphQLFilterVisitor(context).visitNode(
                    SelectorReplacingFilterVisitor(
                        "id",
                        if (reverse) "subject" else "object"
                    ).visitNode(it)
                )
            }
        ).takeIf { it.isNotEmpty() }?.joinToString(" AND ", "WHERE ") ?: ""

        val mappedFields =
            (listOf(
                "'id'" to if (reverse) "subject::Dynamic" else "object"
            ) + nestedFields.map { "'${it.field.name}'" to "arrayDistinct(ARRAY_AGG(${it.field.name}))" })
                .joinToString { (a, b) -> "$a,$b" }
        val joinField = "${if (reverse) "object" else "subject"} AS $joinFieldName"
        return "${getJoinType(field)} (SELECT $joinField, map($mappedFields) as $name FROM $tableRef ${
            nestedFields.joinToString(" ") { it.joinStatement }
        } $whereClause GROUP BY ${
            (if (reverse) REVERSED_SORT_COLUMNS else SORT_COLUMNS).joinToString(
                prefix = "(",
                postfix = ")"
            )
        } $COLLAPSE_STATE_EXPR $orderBy LIMIT $offset, $pageSize BY ${if (reverse) "object" else "subject"}) ${name}_join ON $parentJoinField = $joinFieldName"
    }

    protected fun getTargetGraphs(): List<String>? {
        val graphDirective = env.document.getDefinitionsOfType(OperationDefinition::class.java)
            .first { it.operation == OperationDefinition.Operation.QUERY }.directivesByName[DIRECTIVE_GRAPH_NAME]?.firstOrNull()
        return graphDirective?.let { directive ->
            directive.getArgument(ARG_IRI_NAME)?.value?.let { value ->
                when (value) {
                    is StringValue -> listOf(value.value)
                    is ArrayValue -> value.values.mapNotNull { (it as? StringValue)?.value }
                    else -> null
                }?.takeIf { it.isNotEmpty() }
            }
        }
    }

    protected fun orderByStatement(field: Field, prefix: String = "", postFix: String = ""): String {
        val orderByValue = field.arguments.find { it.name == ARG_ORDER_BY_NAME }?.value
        return when (orderByValue) {
            is ArrayValue -> orderByValue.values.map { (it as StringValue).value }
            is StringValue -> listOf(orderByValue.value)
            else -> emptyList()
        }.takeIf { it.isNotEmpty() }?.let { fields ->
            "ORDER BY ${fields.joinToString { prefix + (if (it.startsWith("-")) "${it.substring(1)} DESC" else it.toString()) + postFix }} "
        } ?: ""
    }

    protected fun getJoinType(field: Field): String =
        if (field.hasDirective(KvasirDirectives.optionalDirective.name)) "LEFT JOIN" else "JOIN"

    protected fun getNestedFields(
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
        return FieldInfo(
            processedFields
                .filterNot { it.field.name == FIELD_ID_NAME || it.field.name.startsWith("__") } // Ignore id and system fields
                .filter {
                    it.field.getDirectiveArg<StringValue>(
                        DIRECTIVE_STORAGE_NAME,
                        ARG_CLASS_NAME
                    )?.value == null
                } // Ignore fields that will be loaded from a different storage backend
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
    protected fun getArgsFilter(field: Field): Node? {
        val argFilters =
            field.arguments.filter { it.name !in SchemaGenerator.defaultRelationArguments.map { it.name } || it.name == ARG_ID_NAME }
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


    protected fun unboxScalar(scalar: ScalarValue<*>): String {
        return when (scalar) {
            is StringValue -> scalar.value.toString()
            is BooleanValue -> scalar.isValue.toString()
            is FloatValue -> scalar.value.toString()
            else -> throw IllegalArgumentException("Scalar type '${scalar::class.simpleName}' is not supported as argument")
        }
    }

    protected fun getNodeFilter(field: Field, fieldDefinition: GraphQLFieldDefinition): Node? {
        val subFields = field.selectionSet?.selections?.flatMap {
            if (it is InlineFragment) it.selectionSet.selections else listOf(it)
        }?.filterIsInstance<Field>()?.filterNot { it.name == FIELD_ID_NAME }
            ?.map { it to fieldDefinition.type.innerType<GraphQLFieldsContainer>().getFieldDefinition(it.name) }
        val globalNodeFilter = if (env.executionStepInfo.path.parent.isRootPath) {
            (field.getDirectiveArg<StringValue>(DIRECTIVE_FILTER_NAME, ARG_IF_NAME)
                ?: fieldDefinition.getDirectiveArg(DIRECTIVE_FILTER_NAME, ARG_IF_NAME))
                ?.let {
                    val rsqlParser = RSQLParser()
                    rsqlParser.parse(it.value)
                }
        } else {
            null
        }
        val subFieldFilters = subFields?.map { (subField, subFieldDefinition) ->
            (subField.getDirectiveArg<StringValue>(DIRECTIVE_FILTER_NAME, ARG_IF_NAME)
                ?: subFieldDefinition.getDirectiveArg(DIRECTIVE_FILTER_NAME, ARG_IF_NAME))
                ?.let {
                    val rsqlParser = RSQLParser()
                    rsqlParser.parse(it.value).accept(SelectorReplacingFilterVisitor(SELF_REF_SELECTOR, subField.name))
                }
        } ?: emptyList()
        return (listOf(globalNodeFilter) + subFieldFilters).filterNotNull().takeIf { it.isNotEmpty() }?.let {
            if (it.size == 1) it.first() else AndNode(it)
        }
    }

    protected fun getFQName(name: String): String {
        return JsonLdHelper.getFQName(name, context, "_")?.takeIf { it != name }
            ?: throw IllegalArgumentException("No semantic context found for $name")
    }

    protected fun typeFilter(requiredTypes: List<String>): Node {
        return AndNode(
            listOf(
                ComparisonNode(RSQLOperators.EQUAL, "predicate", listOf(RDFVocab.type)),
                ComparisonNode(RSQLOperators.IN, "object", requiredTypes)
            )
        )
    }

    protected fun getRelationshipFilter(): String? {
        val targetSubject = env.getFromSource<Any>(FIELD_ID_NAME)
        val targetPredicate = getFQName(targetFieldDefinition, context)
        return targetSubject?.let { "subject IN (SELECT object FROM $tableRef WHERE subject = '$targetSubject' AND predicate = '$targetPredicate')" }
    }

}

data class FieldInfo(val fieldSelection: List<SelectedField>)

data class SQLQuery(val sql: String, val columns: List<String>)

data class FieldToJoin(val field: Field, val typeFilter: Node?)
data class SelectedField(val field: Field, val joinStatement: String)