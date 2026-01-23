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