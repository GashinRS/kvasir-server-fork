package kvasir.plugins.policyagent.openfga.config

import io.smallrye.config.ConfigMapping

@ConfigMapping(prefix = "kvasir.pep.openfga")
interface OpenFgaPepConfig {

    fun url(): String
}