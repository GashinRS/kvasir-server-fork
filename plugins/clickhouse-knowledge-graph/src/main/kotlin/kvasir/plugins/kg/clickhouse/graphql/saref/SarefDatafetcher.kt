package kvasir.plugins.kg.clickhouse.graphql.saref

import graphql.Scalars
import graphql.language.Field
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.schema.*
import io.vertx.core.json.JsonArray
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.graphql.ARG_REVERSE_NAME
import kvasir.definitions.kg.graphql.DIRECTIVE_PREDICATE_NAME
import kvasir.definitions.kg.graphql.FIELD_ID_NAME
import kvasir.definitions.rdf.SAREFVocab
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.graphql.*
import kvasir.plugins.kg.clickhouse.specs.GenericQuerySpec
import kvasir.plugins.kg.clickhouse.specs.TIME_SERIES_DATA_TABLE
import kvasir.plugins.kg.clickhouse.specs.TIME_SERIES_TABLE
import kvasir.plugins.kg.clickhouse.utils.ClickhouseUtils
import kvasir.plugins.kg.clickhouse.utils.RDFUtils
import kvasir.plugins.kg.clickhouse.utils.databaseFromPodId
import kvasir.utils.graphql.getFQName
import kvasir.utils.graphql.getFromSource
import kvasir.utils.graphql.getPaginationInfo
import kvasir.utils.graphql.innerType
import java.time.Instant
import java.util.concurrent.CompletionStage

@ApplicationScoped
class SarefDatafetcher(
    private val clickhouseClient: ClickhouseClient
) : DatafetcherProvider {
    override fun getDatafetcher(podId: String, context: Map<String, Any>, atTimestamp: Instant?): DataFetcher<Any> {
        return DataFetcher<Any> { env ->
            val databaseName = databaseFromPodId(podId)

            if (env.executionStepInfo.path.parent.isRootPath) {
                // Root observation type query
                handle(env, context, atTimestamp, databaseName)
            } else if (isReverseLookup(env)) {
                val relation = getFQName(env.fieldDefinition, context)
                val target: String = env.getFromSource<String>(FIELD_ID_NAME)!!
                handle(env, context, atTimestamp, databaseName, relationFilter = relation to target)
            } else {
                // Query for specific instance
                val id = env.getFromSource<String>(FIELD_ID_NAME)
                if (id != null) {
                    handle(env, context, atTimestamp, databaseName, idFilter = listOf(id))
                } else {
                    null
                }
            }
        }
    }

    private fun isReverseLookup(env: DataFetchingEnvironment): Boolean {
        return env.fieldDefinition.getAppliedDirective(DIRECTIVE_PREDICATE_NAME)?.getArgument(ARG_REVERSE_NAME)
            ?.getValue<Boolean>() == true
    }

    private fun handle(
        env: DataFetchingEnvironment,
        context: Map<String, Any>,
        atTimestamp: Instant?,
        databaseName: String,
        idFilter: List<String>? = null,
        relationFilter: Pair<String, String>? = null
    ): CompletionStage<List<Map<String, Any>>>? {
        // Fetch SAREF special field names
        val sarefSpecialFields = env.mergedField.singleField.selectionSet?.let { selectionSet ->
            selectionSet.selections.filterIsInstance<Field>()
                .filterNot { (it.name == FIELD_ID_NAME || it.name.startsWith("__")) }
                .filter { getFQName(it, context) in setOf(SAREFVocab.hasTimestamp, SAREFVocab.hasValue) }
                .map { it.name }
        } ?: emptyList()

        // Handle entrypoints
        val sqlConvertor = TSQLConvertor(
            context,
            atTimestamp,
            databaseName,
            TIME_SERIES_DATA_TABLE,
            env.field,
            env.fieldDefinition,
            env,
            SQLConvertorMode.GET_DATA,
            idFilter,
            relationFilter
        )
        val (sql, columns) = sqlConvertor.toSQL()
        return clickhouseClient.query(GenericQuerySpec(databaseName, TIME_SERIES_DATA_TABLE, columns), sql)
            .map { result ->
                result.map { instance ->
                    instance.mapValues { (key, value) ->
                        if (key != FIELD_ID_NAME && key !in sarefSpecialFields && value is JsonArray) {
                            value.map { RDFUtils.parseLabelValue(it as String) }
                        } else {
                            value
                        }
                    }
                }
            }
            .convert().toCompletionStage()
    }
}

class TSQLConvertor(
    context: Map<String, Any>,
    atTimestamp: Instant?,
    database: String,
    table: String,
    targetField: Field,
    targetFieldDefinition: GraphQLFieldDefinition,
    env: DataFetchingEnvironment,
    mode: SQLConvertorMode = SQLConvertorMode.GET_DATA,
    private val idFilter: List<String>? = null,
    private val relationFilter: Pair<String, String>? = null
) : SQLConvertor(context, atTimestamp, database, table, targetField, targetFieldDefinition, env, mode) {

    private val outputFieldsContainer =
        GraphQLTypeUtil.unwrapAll(targetFieldDefinition.type) as GraphQLFieldsContainer

    override fun toSQL(): SQLQuery {
        val (pageSize, offset) = targetField.getPaginationInfo()
        val orderBy = orderByStatement(targetField)
        val processedFields = targetField.selectionSet.selections.flatMap { selection ->
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
        }.filterNot { it.field.name == FIELD_ID_NAME || it.field.name.startsWith("__") }

        val fieldExprs = processedFields
            .map { field ->
                val fieldName = field.field.name
                val fieldDefinition = outputFieldsContainer.getFieldDefinition(fieldName)
                val fqName = getFQName(fieldDefinition, context)
                val selector = when (fqName) {
                    SAREFVocab.hasTimestamp -> "timestamp"
                    SAREFVocab.hasValue -> when (fieldDefinition.type.innerType<GraphQLScalarType>()) {
                        Scalars.GraphQLInt, Scalars.GraphQLFloat -> "value_number"
                        Scalars.GraphQLBoolean -> "value_bool"
                        else -> "value_string"
                    }

                    else -> "labels['$fqName']"
                }
                "$selector AS $fieldName"
            }
        val whereClause = listOfNotNull(
            atTimestamp?.let { "change_request_ts <= '${ClickhouseUtils.convertInstant(it)}'" },
            idFilter?.takeIf { it.isNotEmpty() }?.let { "id IN (${it.joinToString()})" },
            relationFilter?.let { "series_id IN (SELECT series_id FROM $database.$TIME_SERIES_TABLE WHERE label_name_value = '${it.first}=${it.second}')" },
            getNodeFilter(targetField, targetFieldDefinition)?.let { GraphQLFilterVisitor(context).visitNode(it) },
            getArgsFilter(targetField)?.let { GraphQLFilterVisitor(context).visitNode(it) }
        ).takeIf { it.isNotEmpty() }?.joinToString(" AND ", "WHERE ") ?: ""
        // TODO: double select is a workaround for the filter statements to work (these cannot be aggregate expressions). Check if we can clean this up!
        val subQuery = "SELECT id, ${fieldExprs.joinToString()} FROM $tableRef $whereClause"
        return when (mode) {
            SQLConvertorMode.GET_DATA -> SQLQuery(
                "SELECT id, ${processedFields.joinToString { "arrayDistinct(ARRAY_AGG(${it.field.name})) as ${it.field.name}" }} FROM ($subQuery) GROUP BY id $orderBy LIMIT $offset, $pageSize",
                listOf("id") + processedFields.map { it.field.name })

            SQLConvertorMode.COUNT -> SQLQuery(
                "SELECT count(*) AS totalCount FROM ($subQuery)",
                listOf("totalCount")
            )
        }
    }


}