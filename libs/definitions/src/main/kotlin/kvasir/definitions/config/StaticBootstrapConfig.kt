package kvasir.definitions.config

import io.smallrye.config.ConfigMapping

@ConfigMapping(prefix = "kvasir.bootstrap")
interface StaticBootstrapConfig {

    fun pods(): List<StaticPodConfig>

}

interface StaticPodConfig {
    fun name(): String

    fun defaultPrefixes(): Map<String, String>
}