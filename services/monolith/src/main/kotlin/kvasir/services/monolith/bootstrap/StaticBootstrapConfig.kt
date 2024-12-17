package kvasir.services.monolith.bootstrap

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import io.smallrye.config.WithName
import java.util.Optional

@ConfigMapping(prefix = "kvasir.bootstrap")
interface StaticBootstrapConfig {

    fun pods(): List<StaticPodConfig>

}

interface StaticPodConfig {
    fun name(): String

    // When no auth-config is provided, the default is used
    fun authConfiguration(): Optional<AuthConfigurationConfig>

    @WithDefault("false")
    @WithName("auto-ingest-rdf")
    fun autoIngestRDF(): Boolean

    fun defaultContext(): Map<String, String>
}


interface AuthConfigurationConfig {
    fun serverUrl(): String

    fun clientId(): String

    fun clientSecret(): String
}