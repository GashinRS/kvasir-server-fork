package kvasir.definitions.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import io.smallrye.config.WithName

@ConfigMapping(prefix = "kvasir.bootstrap")
interface StaticBootstrapConfig {

    fun pods(): List<StaticPodConfig>

}

interface StaticPodConfig {
    fun name(): String

    @WithDefault("false")
    @WithName("auto-ingest-rdf")
    fun autoIngestRDF(): Boolean

    fun defaultPrefixes(): Map<String, String>
}