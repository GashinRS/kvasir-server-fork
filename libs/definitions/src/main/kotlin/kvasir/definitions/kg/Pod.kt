package kvasir.definitions.kg

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import io.vertx.core.json.JsonObject
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.annotations.Persistent
import kvasir.definitions.annotations.StorageLevel
import kvasir.definitions.auth.AuthConstants
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
    override val createdBy: String,
    var configuration: String
) : PersistentEntity() {

    @JsonIgnore
    fun getConfigAsJson(): JSONObject {
        return JsonObject(configuration).map
    }

    /**
     * Applies a new configuration to this pod using a deep-merge strategy:
     * - Keys absent from [newConfiguration] fall back to the current configuration.
     * - Nested objects are merged recursively.
     * - String values equal to [AuthConstants.REDACTED_CREDENTIAL] are replaced with the
     *   current value, so clients that receive a redacted GET response can safely PUT it back
     *   without corrupting stored credentials.
     * - An explicit `null` in [newConfiguration] clears the corresponding field.
     */
    @JsonIgnore
    fun applyConfigurationUpdate(newConfiguration: String): Pod {
        val origCfg = JsonObject(configuration)
        val newCfg = JsonObject(newConfiguration)
        return this.copy(configuration = deepMerge(origCfg, newCfg).encode())
    }

    private fun deepMerge(base: JsonObject, update: JsonObject, path: List<String> = emptyList()): JsonObject {
        val result = update.copy()
        for (key in base.fieldNames()) {
            val baseValue = base.getValue(key)
            val updateValue = result.getValue(key)
            val currentPath = path + key
            when {
                updateValue == null ->
                    // Explicit null in update: intentional clear, keep as-is.
                    Unit
                currentPath !in ATOMIC_MAP_CONFIG_PATHS && baseValue is JsonObject && updateValue is JsonObject ->
                    // Both sides are structured config objects: merge recursively.
                    // Fully-qualified paths of Map<String, String> config properties
                    // (listed in ATOMIC_MAP_CONFIG_PATHS) are excluded so that their values are
                    // replaced wholesale instead.
                    result.put(key, deepMerge(base.getJsonObject(key), result.getJsonObject(key), currentPath))
                updateValue is String && updateValue == AuthConstants.REDACTED_CREDENTIAL ->
                    // Redacted sentinel: restore the original value so round-trips don't corrupt credentials.
                    result.put(key, baseValue)
            }
        }
        return result
    }

    companion object {
        /**
         * Fully-qualified JSON paths (from the config root) of properties that correspond to
         * [Map]<[String], [String]> in the KvasirConfig interfaces. These are treated as atomic
         * values during configuration merging: when present in the update they completely replace
         * the original rather than being deep-merged key-by-key.
         *
         * Using full paths (rather than bare key names) prevents false matches if a future
         * structured config object happens to share a key name.
         *
         * - `["default-context"]`: [kvasir.definitions.config.PodConfig.defaultContext]
         * - `["auth", "oidc", "principal-extractor", "config"]`: [kvasir.definitions.config.JWTPrincipalExtractorConfig.config] (via OIDC)
         * - `["auth", "uma", "principal-extractor", "config"]`: [kvasir.definitions.config.JWTPrincipalExtractorConfig.config] (via UMA)
         */
        private val ATOMIC_MAP_CONFIG_PATHS = setOf(
            listOf("default-context"),
            listOf("auth", "oidc", "principal-extractor", "config"),
            listOf("auth", "uma", "principal-extractor", "config"),
        )
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