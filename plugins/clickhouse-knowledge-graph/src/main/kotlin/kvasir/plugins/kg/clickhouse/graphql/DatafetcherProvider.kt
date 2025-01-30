package kvasir.plugins.kg.clickhouse.graphql

import graphql.schema.DataFetcher
import java.time.Instant

interface DatafetcherProvider {

    fun getDatafetcher(
        podId: String,
        context: Map<String, Any>,
        atTimestamp: Instant?
    ): DataFetcher<Any>

}