package kvasir.services.init

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithConverter
import io.smallrye.config.WithDefault
import io.smallrye.config.WithName
import io.vertx.core.json.JsonObject
import kvasir.definitions.annotations.GenerateNoArgConstructor
import org.apache.kafka.common.protocol.types.Field.Bool
import org.eclipse.microprofile.config.spi.Converter
import java.util.Optional

@ConfigMapping(prefix = "kvasir.bootstrap")
interface StaticBootstrapConfig {

    fun pods(): List<StaticPodConfig>

}

interface StaticPodConfig {
    fun name(): String

    // When no auth-config is provided, the default is used
    fun authConfiguration(): Optional<AuthConfigurationConfig>

    fun preconfiguredClients(): Optional<List<ClientConfig>>

    @WithDefault("false")
    @WithName("auto-ingest-rdf")
    fun autoIngestRDF(): Boolean

    @WithConverter(JsonConvertor::class)
    fun defaultContext(): Map<String, Any>
}


interface AuthConfigurationConfig {
    fun serverUrl(): String

    fun clientId(): String

    fun clientSecret(): String
}

interface ClientConfig {
    fun clientId(): String

    @WithDefault("false")
    fun enableServiceAccount(): Boolean

    fun clientSecret(): Optional<String>

    fun redirectUris(): Optional<List<String>>
}

class JsonConvertor : Converter<Map<String, Any>> {
    override fun convert(input: String): Map<String, Any> {
        return JsonObject(input).map
    }

}