package kvasir.plugins.kg.clickhouse.client

import io.smallrye.config.ConfigMapping
import java.util.*

@ConfigMapping(prefix = "kvasir.kg.clickhouse")
interface ClickhouseClientConfig {
    fun host(): String
    fun port(): Int
    fun user(): Optional<String>
    fun password(): Optional<String>
}