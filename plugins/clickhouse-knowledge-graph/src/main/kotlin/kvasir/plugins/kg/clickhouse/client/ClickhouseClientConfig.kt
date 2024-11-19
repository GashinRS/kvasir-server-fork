package idlab.obelisk.core.plugins.datastore.clickhouse.client

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.util.*

@ConfigMapping(prefix = "kvasir.kg.clickhouse")
interface ClickhouseClientConfig {
    @WithDefault("localhost")
    fun host(): String

    @WithDefault("8123")
    fun port(): Int
    fun user(): Optional<String>
    fun password(): Optional<String>
}