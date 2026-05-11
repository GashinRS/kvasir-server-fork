package kvasir.plugins.kg.clickhouse.client

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.util.*

@ConfigMapping(prefix = "kvasir.kg.clickhouse")
interface ClickhouseClientConfig {
    fun host(): String
    fun port(): Int
    fun user(): Optional<String>
    fun password(): Optional<String>

    @WithDefault("1")
    fun optimizeAggregationInOrder(): Int

    @WithDefault("1073741824")
    fun maxBytesBeforeExternalGroupBy(): Long

    @WithDefault("1073741824")
    fun maxBytesBeforeExternalSort(): Long

    @WithDefault("grace_hash")
    fun joinAlgorithm(): String
}
