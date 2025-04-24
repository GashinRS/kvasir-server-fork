package kvasir.plugins.kg.clickhouse.graphql

import cz.jirutka.rsql.parser.RSQLParser
import cz.jirutka.rsql.parser.ast.AndNode
import cz.jirutka.rsql.parser.ast.ComparisonNode
import cz.jirutka.rsql.parser.ast.Node
import cz.jirutka.rsql.parser.ast.OrNode
import cz.jirutka.rsql.parser.ast.RSQLOperators
import graphql.language.*
import graphql.schema.*
import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import io.vertx.core.json.JsonObject
import jakarta.enterprise.context.ApplicationScoped
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
import kvasir.utils.graphql.*
import kvasir.utils.json.convertToJsonMap
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
            val returnList = env.fieldDefinition.type.isList()
            try {
                (if (env.executionStepInfo.path.parent.isRootPath) {
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
                            val instances = result.map { instance -> instance.mapKeys { e -> e.key.removePrefix("_") } }
                            if (env.fieldType.isAbstract()) handleFragments(env, instances, context) else instances
                        }
                } else if (env.fieldType.isAbstract()) {
                    val target = env.getFromSource<Iterable<Any>>(env.field.aliasOrName())!!
                    Uni.createFrom().item(handleFragments(env, target, context))
                } else if (env.field.name == "_rawRDF") {
                    val record =
                        (env.getFromSource<Iterable<Any>>(env.field.aliasOrName())!!.first() as Iterable<Any>).toList()
                    val rawValue = record[0]
                    val datatype = (record[1] as String).takeIf { it.isNotBlank() }
                    Uni.createFrom().item(
                        when {
                            datatype != null -> mapOf(JsonLdKeywords.value to rawValue, JsonLdKeywords.type to datatype)
                            else -> mapOf(JsonLdKeywords.id to rawValue)
                        }
                    )
                } else {
                    val value = env.getFromSource<Any>(env.field.aliasOrName())

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
                                it[FIELD_ID_NAME] as String
                            }
                        )
                        val (sql, columns) = sqlConvertor.toSQL()
                        clickhouseClient.query(GenericQuerySpec(databaseName, DATA_TABLE, columns), sql)
                            .map { result ->
                                result.map { instance -> instance.mapKeys { e -> e.key.removePrefix("_") } }
                            }
                    } else {
                        Uni.createFrom().item(value)
                    }
                }).map { output ->
                    when {
                        output is Iterable<*> && returnList -> output.filterNotNull()
                        output is Iterable<*> -> output.firstOrNull()
                        returnList -> listOf(output)
                        else -> output
                    }
                }.convert().toCompletionStage()
            } catch (err: Throwable) {
                Log.warn("Error while resolving GraphQL query.", err)
                CompletableFuture.failedStage<Any>(err)
            }
        }
    }

    private fun handleFragments(env: DataFetchingEnvironment, value: Any?, context: Map<String, Any>): Any {
        val target = if (value is Iterable<*>) value else listOf(value)
        val result = target.filterNotNull().map { result ->
            val obj = convertToJsonMap(result)

            // Obj without fragment wrappers, plus...
            obj.filterNot { it.key.startsWith("__fragment") }
                .plus(
                    // The fragment values
                    obj.keys.sorted()
                        .filter { it.startsWith("__fragment_") }
                        .mapNotNull { fragmentKey -> (obj[fragmentKey] as Iterable<Any>).firstOrNull() }
                        .flatMap { fragmentValue -> convertToJsonMap(fragmentValue).toList() }
                )
        }
        return result
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
                val selectedFields =
                    env.field.selectionSet.selections.filterIsInstance<Field>().map { f -> f.aliasOrName() }
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
        val outputType = GraphQLTypeUtil.unwrapAll(targetFieldDefinition.type) as GraphQLOutputType
        val (pageSize, offset) = targetField.getPaginationInfo(env.variables)
        val orderBy = orderByStatement(targetField, "_")
        val idField = when (mode) {
            SQLConvertorMode.GET_DATA -> "_id"
            SQLConvertorMode.COUNT -> "subject"
        }
        val whereClause = listOfNotNull(
            targetGraphFilterNode,
            outputType.takeIf { !KvasirTypes.all.contains(it) }?.let { typeFilter(it) },
            getNodeFilter(targetField, targetFieldDefinition, targetFieldDefinition.type.innerType()),
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
            targetFieldDefinition,
            idField
        )
        val projection =
            (
                    listOf("subject AS $idField") + nestedFields.map {
                        val baseFieldProj = "arrayDistinct(ARRAY_AGG(${it.fieldName}))"
                        (if (it.nested) "arrayFilter(x -> notEmpty(x), $baseFieldProj)" else baseFieldProj)
                            .plus(" AS _${it.fieldName}")
                    }
                    ).joinToString()
        return when (mode) {
            SQLConvertorMode.GET_DATA -> {
                SQLQuery(
                    "SELECT $projection FROM $tableRef ${
                        nestedFields.joinToString(" ") { it.joinStatement }
                    } $whereClause GROUP BY subject $orderBy LIMIT $offset, $pageSize",
                    listOf(idField) + nestedFields.map { "_${it.fieldName}" }
                )
            }

            SQLConvertorMode.COUNT -> {
                // TODO: what was the point of this modifiedWhere?
                val modifiedWhere = /*getRelationshipFilter()?.let { extraFilter ->
                    if (whereClause.isNotEmpty()) {
                        "$whereClause AND $extraFilter"
                    } else {
                        "WHERE $extraFilter"
                    }
                } ?:*/ whereClause
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
        fieldDefinition: GraphQLFieldDefinition?,
        parentJoinField: String,
        overrideJoinType: String? = null
    ): String {
        val name = field.aliasOrName()
        val (pageSize, offset) = field.getPaginationInfo(env.variables)
        val joinField = "${name}_holder"

        // Implement Handling for special scalar fields e.g. _types, _relations, _predicates
        val (targetFilter, targetSelector) = when (field.name) {
            FIELD_TYPES_NAME -> "predicate = '${RDFVocab.type}'" to "object"
            FIELD_RELATIONS_NAME -> {
                val idFilter = getArgsFilter(field)?.let {
                    val expr = GraphQLFilterVisitor(context).visitNode(
                        SelectorReplacingFilterVisitor(
                            "id",
                            "object"
                        ).visitNode(it)
                    )
                    " AND $expr"
                } ?: ""
                "datatype = '' and language = ''$idFilter" to "predicate"
            }

            FIELD_PREDICATES_NAME -> {
                null to "predicate"
            }

            else -> {
                // Normal behaviour: filter by predicate
                val predicate = getPredicateForField(field, fieldDefinition!!)
                "predicate = '$predicate'" to "object"
            }
        }

        val whereClause = listOfNotNull(
            targetGraphFilterNode?.let { GraphQLFilterVisitor(context).visitNode(it) },
            targetFilter,
            atTimestamp?.let { "timestamp <= '${ClickhouseUtils.convertInstant(it)}'" },
            context[JsonLdKeywords.language]?.let { "(datatype != '${RDFVocab.langString}' OR language = '$it')" }
        ).takeIf { it.isNotEmpty() }?.joinToString(" AND ", "WHERE ") ?: ""
        val joinType = overrideJoinType ?: getJoinType(field)
        return "$joinType (SELECT subject AS $joinField, $targetSelector AS $name FROM $tableRef $whereClause GROUP BY ${
            SORT_COLUMNS.joinToString(
                prefix = "(",
                postfix = ")"
            )
        } $COLLAPSE_STATE_EXPR LIMIT $offset, $pageSize BY subject) ${name}_join ON $parentJoinField = $joinField"
    }

    fun rawRDFFieldJoinStatement(
        parentField: Field,
        parentFieldDefinition: GraphQLFieldDefinition,
        parentJoinField: String
    ): String {
        val name = "_rawRDF"
        val (pageSize, offset) = parentField.getPaginationInfo(env.variables)
        val reverse = parentFieldDefinition.getAppliedDirective(DIRECTIVE_PREDICATE_NAME)?.getArgument(ARG_REVERSE_NAME)
            ?.getValue<Boolean>() ?: false
        val joinFieldName = "${name}_holder"
        val selector = if (parentJoinField == "_id") {
            // Select by type
            val fqType = getFQName(parentField, context)
            "(predicate = '${RDFVocab.type}' AND object = '$fqType')"
        } else {
            // Select by predicate
            "predicate = '${getPredicateForField(parentField, parentFieldDefinition)}'"
        }
        val whereClause = listOfNotNull(
            targetGraphFilterNode?.let { GraphQLFilterVisitor(context).visitNode(it) },
            selector,
            atTimestamp?.let { "timestamp <= '${ClickhouseUtils.convertInstant(it)}'" }
        ).takeIf { it.isNotEmpty() }?.joinToString(" AND ", "WHERE ") ?: ""

        val joinField = "${if (reverse) "object" else "subject"} AS $joinFieldName"
        val valueExpr = "${if (reverse) "[subject, '', '']" else "[object, datatype, language]"} AS $name"
        return "${getJoinType(parentField)} (SELECT $joinField, $valueExpr FROM $tableRef $whereClause GROUP BY ${
            (if (reverse) REVERSED_SORT_COLUMNS else SORT_COLUMNS).joinToString(
                prefix = "(",
                postfix = ")"
            )
        } $COLLAPSE_STATE_EXPR LIMIT $offset, $pageSize BY ${if (reverse) "object" else "subject"}) ${name}_join ON $parentJoinField = $joinFieldName"
    }

    fun relationFieldJoinStatement(
        field: Field,
        fieldDefinition: GraphQLFieldDefinition,
        parentJoinField: String
    ): String {
        val name = field.aliasOrName()
        val outputType = fieldDefinition.type.innerType<GraphQLFieldsContainer>()
        val reverse = fieldDefinition.getAppliedDirective(DIRECTIVE_PREDICATE_NAME)?.getArgument(ARG_REVERSE_NAME)
            ?.getValue<Boolean>() ?: false
        val (pageSize, offset) = field.getPaginationInfo(env.variables)
        val joinFieldName = "${name}_holder"
        // The effective subject for this relation field is the object of the parent field, but this changes when the relation is reversed.
        val relSubj = if (reverse) "subject" else "object"
        val (nestedFields) = getNestedFields(
            field,
            fieldDefinition,
            relSubj
        )
        val orderBy = orderByStatement(field, "$name['", "']")
        val whereClause = listOfNotNull(
            targetGraphFilterNode?.let { GraphQLFilterVisitor(context).visitNode(it) },
            "predicate = '${getPredicateForField(field, fieldDefinition)}'",
            atTimestamp?.let { "timestamp <= '${ClickhouseUtils.convertInstant(it)}'" },
            fieldDefinition.type.innerType<GraphQLOutputType>().takeIf { !KvasirTypes.all.contains(it) }
                ?.let { typeFilter(it) }
                ?.let {
                    "$relSubj IN (SELECT subject FROM $tableRef WHERE ${
                        GraphQLFilterVisitor(
                            context
                        ).visitNode(it)
                    })"
                },
            getNodeFilter(field, fieldDefinition, outputType)?.let { GraphQLFilterVisitor(context).visitNode(it) },
            getArgsFilter(field)?.let {
                GraphQLFilterVisitor(context).visitNode(
                    SelectorReplacingFilterVisitor("id", relSubj).visitNode(it)
                )
            }
        ).takeIf { it.isNotEmpty() }?.joinToString(" AND ", "WHERE ") ?: ""

        val mappedFields =
            (listOf(
                "'id'" to if (reverse) "subject::Dynamic" else "object"
            ) + nestedFields.map {
                "'${it.fieldName}'" to if (it.nested) {
                    "arrayFilter(x -> notEmpty(x), arrayDistinct(ARRAY_AGG(${it.fieldName})))"
                } else {
                    "arrayDistinct(ARRAY_AGG(${it.fieldName}))"
                }
            })
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

    fun fragmentJoinStatement(
        fragment: InlineFragment,
        parentField: Field,
        parentFieldDefinition: GraphQLFieldDefinition,
        parentJoinField: String,
        optional: Boolean
    ): String {
        val name = "__fragment_${fragment.typeCondition.name}"
        val outputType: GraphQLFieldsContainer = env.graphQLSchema.getTypeAs(fragment.typeCondition.name)
        val joinFieldName = "${name}_holder"
        val (pageSize, offset) = parentField.getPaginationInfo(env.variables)
        val (nestedFields) = getNestedFields(
            fragment,
            parentFieldDefinition,
            "subject",
            outputType,
            false
        )
        val orderBy = orderByStatement(parentField, "$name['", "']")
        val whereClause = listOfNotNull(
            "subject IN (SELECT subject FROM $tableRef WHERE predicate = '${RDFVocab.type}' AND object = '${
                getFQName(
                    fragment.typeCondition.name
                )
            }')",
            targetGraphFilterNode?.let { GraphQLFilterVisitor(context).visitNode(it) },
            atTimestamp?.let { "timestamp <= '${ClickhouseUtils.convertInstant(it)}'" },
            getNodeFilter(
                fragment,
                parentFieldDefinition,
                outputType
            )?.let { GraphQLFilterVisitor(context).visitNode(it) }
        ).takeIf { it.isNotEmpty() }?.joinToString(" AND ", "WHERE ") ?: ""

        val mappedFields =
            (listOf(
                "'id'" to "subject::Dynamic",
                "'__typename'" to "'${fragment.typeCondition.name}'"
            ) + nestedFields.map { "'${it.fieldName}'" to "arrayDistinct(ARRAY_AGG(${it.fieldName}))" })
                .joinToString { (a, b) -> "$a,$b" }
        val joinField = "subject AS $joinFieldName"
        val joinType = if (optional) "LEFT JOIN" else "JOIN"
        return "$joinType (SELECT $joinField, map($mappedFields) as $name FROM $tableRef ${
            nestedFields.joinToString(" ") { it.joinStatement }
        } $whereClause GROUP BY ${
            SORT_COLUMNS.joinToString(
                prefix = "(",
                postfix = ")"
            )
        } $COLLAPSE_STATE_EXPR $orderBy LIMIT $offset, $pageSize BY subject) ${name}_join ON $parentJoinField = $joinFieldName"
    }

    protected fun getTargetGraphs(): List<String>? {
        val graphDirective = env.document.getDefinitionsOfType(OperationDefinition::class.java)
            .firstOrNull { it.operation == OperationDefinition.Operation.QUERY }?.directivesByName?.get(
                DIRECTIVE_GRAPH_NAME
            )?.firstOrNull()
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
        val orderByValue = field.getStringArrayArgument(ARG_ORDER_BY_NAME, env.variables)
        return orderByValue?.takeIf { it.isNotEmpty() }?.let { fields ->
            "ORDER BY ${fields.joinToString { prefix + (if (it.startsWith("-")) "${it.substring(1)} DESC" else it.toString()) + postFix }} "
        } ?: ""
    }

    protected fun getJoinType(field: DirectivesContainer<*>): String =
        if (field.hasDirective(KvasirDirectives.optionalDirective.name)) "LEFT JOIN" else "JOIN"

    protected fun getNestedFields(
        field: SelectionSetContainer<*>,
        fieldDefinition: GraphQLFieldDefinition,
        parentJoinField: String,
        selectedOutputType: GraphQLFieldsContainer? = null,
        includeTypes: Boolean = true
    ): FieldInfo {
        val outputDefinition: GraphQLCompositeType = selectedOutputType ?: fieldDefinition.type.innerType()
        val allProcessedFields =
            if (outputDefinition is GraphQLInterfaceType || outputDefinition is GraphQLObjectType) {
                val fieldDefinitions = when (outputDefinition) {
                    is GraphQLObjectType -> outputDefinition.fieldDefinitions
                    is GraphQLInterfaceType -> outputDefinition.fieldDefinitions
                    else -> emptyList()
                }
                val processedFields = field.selectionSet.selections.filterIsInstance<Field>()
                val includedFieldNames = processedFields.map { it.name }.toSet()
                val availableFields = fieldDefinitions.map { it.name }.toSet()
                // Fetch fields not in the selection but have predefined filters defined in the schema.
                val predefinedFilterFields = fieldDefinitions.filter { fieldDef ->
                    !includedFieldNames.contains(fieldDef.name) && fieldDef.getDirective(
                        "filter"
                    ) != null
                }
                    .map { Field.newField().name(it.name).build() }
                // Fetch fields referenced in argument filters that are not present in the selection.
                val argFilterFields =
                    if (field is Field) field.arguments.filter { arg ->
                        availableFields.contains(arg.name) && !includedFieldNames.contains(
                            arg.name
                        )
                    }
                        .map { Field.newField().name(it.name).build() } else emptyList()
                (processedFields + predefinedFilterFields + argFilterFields).filterNot {
                    it.name == FIELD_ID_NAME || it.name == "_types" || it.name.startsWith("__")
                }
                    .filter {
                        it.getDirectiveArg<StringValue>(
                            DIRECTIVE_STORAGE_NAME,
                            ARG_CLASS_NAME
                        )?.value == null
                    } // Ignore fields that will be loaded from a different storage backend
                    .map { nestedField ->
                        val nestedFieldDefinition = fieldDefinitions.find { it.name == nestedField.name }
                            ?: throw RuntimeException("Unexpected error: could not find definition for nested field '${nestedField.name}'")
                        SelectedField(
                            nestedField.aliasOrName(),
                            nestedField.selectionSet != null,
                            if (nestedField.selectionSet == null) {
                                if (nestedField.name == "_rawRDF") {
                                    // Special _rawRDF scalar handling
                                    rawRDFFieldJoinStatement(
                                        field as Field,
                                        fieldDefinition,
                                        if (parentJoinField == "_id") parentJoinField else "subject"
                                    )
                                } else {
                                    // Scalar field
                                    scalarFieldJoinStatement(
                                        nestedField,
                                        nestedFieldDefinition,
                                        parentJoinField
                                    )
                                }
                            } else {
                                // Relation field
                                relationFieldJoinStatement(
                                    nestedField,
                                    nestedFieldDefinition,
                                    parentJoinField
                                )
                            }
                        )
                    }
            } else {
                emptyList()
            }.plus(
                if (includeTypes) {
                    listOf(
                        SelectedField(
                            "_types",
                            false,
                            scalarFieldJoinStatement(
                                Field.newField("_types").build(),
                                null,
                                parentJoinField,
                                "LEFT JOIN"
                            )
                        )
                    )
                } else {
                    emptyList()
                }
            )

        val inlineFragments = field.selectionSet.selections.filterIsInstance<InlineFragment>()
        val processedFragments = inlineFragments.map { fragment ->
            SelectedField(
                "__fragment_${fragment.typeCondition.name}",
                true,
                fragmentJoinStatement(
                    fragment,
                    field as Field,
                    fieldDefinition,
                    parentJoinField,
                    allProcessedFields.isNotEmpty() || inlineFragments.size > 1
                )
            )
        }
        return FieldInfo(allProcessedFields + processedFragments)
    }

    // TODO: rewrite this quick and dirty implementation
    protected fun getArgsFilter(field: Field): Node? {
        val aliases = field.selectionSet?.selections?.filterIsInstance<Field>()?.filter { it.alias != null }
            ?.associate { it.name to it.alias } ?: emptyMap()
        val argFilters =
            field.arguments.filter { it.name !in KvasirTypes.defaultRelationArguments.map { it.name } || it.name == ARG_ID_NAME }
                .filterNot { field.name == FIELD_OBJECT_NAME && it.name == ARG_PREDICATE_NAME } // Why filter out these arguments?
                .map { argument ->
                    val argFilterName = aliases[argument.name] ?: argument.name
                    when (argument.value) {
                        is ArrayValue -> ComparisonNode(
                            RSQLOperators.IN,
                            argFilterName,
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
                                    argFilterName,
                                    value.map { it.toString() }
                                )
                            } else {
                                ComparisonNode(
                                    RSQLOperators.EQUAL,
                                    argFilterName,
                                    listOf(value.toString())
                                )
                            }
                        }

                        else -> ComparisonNode(
                            RSQLOperators.EQUAL,
                            argFilterName,
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

    protected fun getNodeFilter(
        target: SelectionSetContainer<*>,
        targetDefinition: GraphQLFieldDefinition,
        outputType: GraphQLFieldsContainer
    ): Node? {
        val subFields = ((target.selectionSet?.selections?.filterIsInstance<Field>()
            ?.filterNot { it.name == FIELD_ID_NAME || it.name.startsWith("__") }
            ?.map { it to outputType.getFieldDefinition(it.name) })
            ?: emptyList())

        val includedFieldNames = subFields.map { it.first.name }.toSet()
        // Fetch fields not in the selection but have predefined filters defined in the schema
        val allSubFields =
            subFields + outputType.fieldDefinitions.filter { fieldDef ->
                !includedFieldNames.contains(
                    fieldDef.name
                ) && fieldDef.getDirective("filter") != null
            }
                .map {
                    val addField = Field.newField().name(it.name).build()
                    addField to it
                }

        target as DirectivesContainer<*>
        val globalNodeFilter = if (env.executionStepInfo.path.parent.isRootPath) {
            (target.getDirectiveArg<StringValue>(DIRECTIVE_FILTER_NAME, ARG_IF_NAME)
                ?: targetDefinition.getDirectiveArg(DIRECTIVE_FILTER_NAME, ARG_IF_NAME))
                ?.let {
                    val rsqlParser = RSQLParser()
                    val parsedFilter = rsqlParser.parse(it.value)
                    val aliasedIdField = target.selectionSet?.selections?.filterIsInstance<Field>()
                        ?.find { it.name == FIELD_ID_NAME && it.alias != null }
                    if (aliasedIdField != null) {
                        parsedFilter.accept(SelectorReplacingFilterVisitor(aliasedIdField.alias, FIELD_ID_NAME))
                    } else {
                        parsedFilter
                    }
                }
        } else {
            null
        }
        val subFieldFilters = allSubFields.map { (subField, subFieldDefinition) ->
            (subField.getDirectiveArg<StringValue>(DIRECTIVE_FILTER_NAME, ARG_IF_NAME)
                ?: subFieldDefinition.getDirectiveArg(DIRECTIVE_FILTER_NAME, ARG_IF_NAME))
                ?.let {
                    val rsqlParser = RSQLParser()
                    rsqlParser.parse(it.value)
                        .accept(SelectorReplacingFilterVisitor(SELF_REF_SELECTOR, subField.aliasOrName()))
                }
        }
        return (listOf(globalNodeFilter) + subFieldFilters).filterNotNull().takeIf { it.isNotEmpty() }?.let {
            if (it.size == 1) it.first() else AndNode(it)
        }
    }

    protected fun getFQName(name: String): String {
        return JsonLdHelper.getFQName(name, context, "_")?.takeIf { it != name }
            ?: throw IllegalArgumentException("No semantic context found for $name")
    }

    protected fun typeFilter(requiredType: GraphQLOutputType): Node? {
        val matchTypes = when (requiredType) {
            is GraphQLInterfaceType -> env.graphQLSchema.getImplementations(requiredType)
            is GraphQLUnionType -> requiredType.types
            else -> listOf(requiredType)
        }.map { getFQName(it as GraphQLDirectiveContainer, context) }
        return if (matchTypes.isNotEmpty()) {
            AndNode(
                listOf(
                    ComparisonNode(RSQLOperators.EQUAL, "predicate", listOf(RDFVocab.type)),
                    ComparisonNode(RSQLOperators.IN, "object", matchTypes)
                )
            )
        } else {
            null
        }
    }

    protected fun getRelationshipFilter(): String? {
        val targetSubject = env.getFromSource<Any>(FIELD_ID_NAME)
        val targetPredicate = getFQName(targetFieldDefinition, context)
        return targetSubject?.let { "subject IN (SELECT object FROM $tableRef WHERE subject = '$targetSubject' AND predicate = '$targetPredicate')" }
    }

    protected fun getPredicateForField(field: Field, fieldDefinition: GraphQLFieldDefinition): String {
        return if (fieldDefinition.name == FIELD_OBJECT_NAME) {
            val predicateArg = field.arguments.find { it.name == ARG_PREDICATE_NAME }!!
            val predicateName = if (predicateArg.value is VariableReference) {
                val value = env.variables[(predicateArg.value as VariableReference).name]!!
                value
            } else {
                unboxScalar(predicateArg.value as ScalarValue<*>)
            } as String
            JsonLdHelper.getFQName(predicateName, context, ":")?.takeIf { it != predicateName }
                ?: predicateName
        } else {
            getFQName(fieldDefinition, context)
        }
    }

}

data class FieldInfo(val fieldSelection: List<SelectedField>)

data class SQLQuery(val sql: String, val columns: List<String>)

data class FieldToJoin(val field: Field, val typeFilter: Node?)
data class SelectedField(val fieldName: String, val nested: Boolean, val joinStatement: String)