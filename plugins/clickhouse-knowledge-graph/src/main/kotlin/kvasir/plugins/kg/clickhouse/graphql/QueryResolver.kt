package kvasir.plugins.kg.clickhouse.graphql

import graphql.schema.DataFetcher

class QueryResolver {

    fun getDatafetcher(
        podId: String,
        context: Map<String, Any>
    ): DataFetcher<Any> {
        return DataFetcher { env ->
            
        }
    }

}