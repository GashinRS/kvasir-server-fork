package kvasir.definitions.kg

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import io.smallrye.mutiny.Uni
import io.vertx.core.json.JsonObject
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import java.time.Instant
import java.util.UUID

interface PodStore {

    fun persist(pod: Pod): Uni<Void>

    fun list(): Uni<List<Pod>>

    fun getById(id: String): Uni<Pod?>

    fun deleteById(id: String): Uni<Void>

}

/**
 * A PodAuthInitializer can be provided by a plugin to initialize the auth configuration for a new pod
 * with the default authorization server (to streamline the process of creating a new pod).
 */
interface PodAuthInitializer {

    fun initialize(podId: String, podName: String, preconfiguredClients: List<ClientConfiguration> = emptyList()): Uni<AuthConfiguration>

}

data class Pod(
    @JsonProperty(JsonLdKeywords.id)
    val id: String,
    @JsonProperty(KvasirVocab.configuration)
    val configuration: Map<String, Any>,
) {

    @JsonIgnore
    fun getDefaultContext(): Map<String, Any> {
        return configuration[PodConfigurationProperty.DEFAULT_CONTEXT]?.let { JsonObject(it as String).map }
            ?: emptyMap()
    }

    @JsonIgnore
    fun getAutoIngestRDF(): Boolean {
        return configuration[PodConfigurationProperty.AUTO_INGEST_RDF] as? Boolean == true
    }

    @JsonIgnore
    fun getAuthConfiguration(): AuthConfiguration? {
        return configuration[KvasirVocab.authConfiguration]?.let {
            JsonObject(it as Map<String, Any>).mapTo(AuthConfiguration::class.java)
        }
    }

}

object PodConfigurationProperty {

    const val DEFAULT_CONTEXT = KvasirVocab.defaultContext
    const val AUTO_INGEST_RDF = KvasirVocab.autoIngestRDF

}

data class AuthConfiguration(
    @JsonProperty(KvasirVocab.serverUrl)
    val serverUrl: String,
    @JsonProperty(KvasirVocab.clientId)
    val clientId: String,
    @JsonProperty(KvasirVocab.clientSecret)
    val clientSecret: String,
)

enum class LifeCycleEventType {
    POD_CREATED,
    POD_UPDATED,
    POD_DELETED,
    SLICE_CREATED,
    SLICE_UPDATED,
    SLICE_DELETED,
}

@JsonInclude(JsonInclude.Include.NON_DEFAULT)
data class LifeCycleEvent(
    @JsonProperty(JsonLdKeywords.context)
    val context: JSONObject = KvasirVocab.context,
    @JsonProperty(JsonLdKeywords.id)
    val id: String = "urn:kvasir:life-cycle-events:${UUID.randomUUID()}",
    @JsonProperty(KvasirVocab.timestamp)
    val timestamp: Instant = Instant.now(),
    @JsonProperty(KvasirVocab.type)
    val type: LifeCycleEventType,
    @JsonProperty(KvasirVocab.podId)
    val podId: String,
    @JsonProperty(KvasirVocab.sliceId)
    val sliceId: String? = null
)

data class ClientConfiguration(
    val clientId: String,
    val enableServiceAccount: Boolean,
    val clientSecret: String? = null,
    val redirectUris: List<String>? = null
)