package kvasir.definitions.kg

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import io.vertx.core.json.JsonObject
import kvasir.definitions.annotations.Persistent
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.annotations.StorageLevel
import kvasir.definitions.persistence.PersistentEntity
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.rdf.getJsonObject
import java.time.Instant
import java.util.*

@GenerateNoArgConstructor
@Persistent(storageLevel = StorageLevel.SYSTEM, collectionName = "pods")
@JsonIgnoreProperties(ignoreUnknown = true)
data class Pod(
    override var id: String,
    var configuration: String
) : PersistentEntity() {

    @JsonIgnore
    fun getConfigAsJson(): JSONObject {
        return JsonObject(configuration).map
    }

    @JsonIgnore
    fun copyAndKeepUmaCredentials(newConfiguration: String): Pod {
        val newCfg = JsonObject(newConfiguration);
        // If not uma config, or uma config but no client_id
        val copyAuth = !newCfg.containsKey("auth")
        val copyUma = !copyAuth && !newCfg.getJsonObject("auth").containsKey("uma")
        val copyClientId = !copyUma && !newCfg.getJsonObject("auth").getJsonObject("uma").containsKey("client-id")
        val copyClientSecret = !copyUma && !newCfg.getJsonObject("auth").getJsonObject("uma").containsKey("client-secret")

        // Get uma section of original config
        val origCfg = JsonObject(configuration);
        val auth = origCfg.getJsonObject("auth") ?: JsonObject();
        if (copyAuth) {
            newCfg.put("auth", auth);
        }

        val uma = auth.getJsonObject("uma") ?: JsonObject();
        if (copyUma) {
            newCfg.put("uma", uma);
        }
        // Copy original clientId if needed
        if (copyClientId) {
            val clientId = uma.getString("client-id")
            if (clientId != null) {
                newCfg.getJsonObject("auth").getJsonObject("uma").put("client-id", clientId)
            }
        }
        // Copy original clientSecret if needed
        if (copyClientSecret) {
            val clientSecret = uma.getString("client-secret")
            if (clientSecret != null) {
                newCfg.getJsonObject("auth").getJsonObject("uma").put("client-secret", clientSecret)
            }
        }
        // Parse newCfg back to string
        return this.copy(configuration = newCfg.encode())
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
    val context: JSONObject = KvasirVocab.context,
    val id: String = "urn:kvasir:life-cycle-events:${UUID.randomUUID()}",
    val requestingUser: String,
    val timestamp: Instant = Instant.now(),
    val eventType: LifeCycleEventType,
    val podId: String,
    val sliceId: String? = null
)