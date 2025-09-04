package kvasir.definitions.kg

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import io.vertx.core.json.JsonObject
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.persistence.PersistentEntity
import kvasir.definitions.persistence.Repository
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.rdf.getJsonObject
import java.time.Instant
import java.util.*

interface PodStoreFactory {
    fun createPodStore(): PodStore
}

interface PodStore : Repository<Pod>

@GenerateNoArgConstructor
data class Pod(
    @get:JsonProperty(JsonLdKeywords.id)
    override var id: String,
    @get:JsonProperty(KvasirVocab.configuration)
    var configuration: Map<String, Any>
) : PersistentEntity() {

    @JsonIgnore
    fun getDefaultContext(): Map<String, Any> {
        return configuration[KvasirVocab.defaultContext]?.let {
            if (it is String && it.isNotEmpty()) {
                JsonObject(it).map
            } else {
                null
            }
        } ?: emptyMap()
    }

    @JsonIgnore
    fun getAutoIngestRDF(): Boolean {
        return configuration[KvasirVocab.autoIngestRDF] as? Boolean == true
    }

    @JsonIgnore
    fun getAuthConfiguration(): Map<String, Any>? {
        return configuration.getJsonObject(KvasirVocab.authConfiguration)
    }

}

enum class LifeCycleEventType {
    POD_CREATED,
    POD_UPDATED,
    POD_DELETED,
    SLICE_CREATED,
    SLICE_UPDATED,
    SLICE_DELETED,
}

@GenerateNoArgConstructor
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
data class LifeCycleEvent(
    @get:JsonProperty(JsonLdKeywords.context)
    val context: JSONObject = KvasirVocab.context,
    @get:JsonProperty(JsonLdKeywords.id)
    val id: String = "urn:kvasir:life-cycle-events:${UUID.randomUUID()}",
    @get:JsonProperty(KvasirVocab.requestingUser)
    val requestingUser: String?,
    @get:JsonProperty(KvasirVocab.timestamp)
    val timestamp: Instant = Instant.now(),
    @get:JsonProperty(KvasirVocab.type)
    val type: LifeCycleEventType,
    @get:JsonProperty(KvasirVocab.podId)
    val podId: String,
    @get:JsonProperty(KvasirVocab.sliceId)
    val sliceId: String? = null
)