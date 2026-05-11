package kvasir.plugins.kg.clickhouse.graphql

import graphql.schema.DataFetchingEnvironment
import io.smallrye.mutiny.Uni
import io.vertx.core.json.JsonArray
import jakarta.enterprise.context.ApplicationScoped
import kvasir.plugins.kg.clickhouse.graphql.resolver.HAS_NEXT
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.graphql.resolver.QueryBuilder
import kvasir.plugins.kg.clickhouse.specs.DATA_TABLE
import kvasir.plugins.kg.clickhouse.specs.GenericQuerySpec
import kvasir.plugins.kg.clickhouse.utils.databaseFromPodId
import kvasir.utils.graphql.aliasOrName
import kvasir.utils.graphql.getFromSource
import kvasir.utils.graphql.isList

@ApplicationScoped
class CHDataFetcher(
    private val clickhouseClient: ClickhouseClient
) {

    fun fetchData(
        env: DataFetchingEnvironment,
        podId: String,
        context: Map<String, Any>,
        atChangeId: String?
    ): Uni<Any?> {
        return if (env.executionStepInfo.path.parent.isRootPath) {
            val databaseName = databaseFromPodId(podId)
            val queryBuilder = QueryBuilder(context, atChangeId, env)
            val columns = queryBuilder.root.children.map { it.nameInResult }
            val sql = queryBuilder.build()
            val paginationInfo = queryBuilder.root.paginationInfo
            clickhouseClient.query(GenericQuerySpec(databaseName, DATA_TABLE, columns), sql)
                .map { result ->
                    paginationInfo?.let { (pageSize, _) ->
                        val hasNext = result.size > pageSize
                        result.take(pageSize).map { row -> row + (HAS_NEXT to hasNext) }
                    } ?: result
                }
        } else {
            val value = env.getFromSource<Any>(env.field.aliasOrName())
            Uni.createFrom().item(value)
        }.map { output ->
            val returnList = env.fieldDefinition.type.isList()
            val isListType = output is List<*> || output is JsonArray
            when {
                // When the output is a list and the field is defined as a list, return the list with nulls filtered out
                isListType && returnList -> output.filterNotNull()
                // When the output is a list, but the field is not defined as a list, return the first item (or null if the list is empty)
                isListType -> output.firstOrNull()
                // When the output is not a list, but the field is defined as a list, wrap the output in a list (or return an empty list if the output is null)
                returnList -> listOfNotNull(output)
                // When the output is not a list and the field is not defined as a list, return the output as is
                else -> output
            }
        }
    }

}
