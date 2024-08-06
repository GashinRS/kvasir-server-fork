package kvasir.services.monolith.bootstrap

import io.smallrye.config.ConfigMapping

@ConfigMapping(prefix = "kvasir.bootstrap")
interface StaticBootstrapConfig {

    fun pods(): List<StaticPodConfig>

}

interface StaticPodConfig {
    fun name(): String
}