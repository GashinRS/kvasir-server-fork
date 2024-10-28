package kvasir.definitions.kg

import com.fasterxml.jackson.annotation.JsonIgnore
import io.smallrye.mutiny.Uni

interface PodStore {

    fun persist(pod: Pod): Uni<Void>

    fun list(): Uni<List<Pod>>

    fun getById(id: String): Uni<Pod?>

    fun deleteById(id: String): Uni<Void>

}

data class Pod(
    val id: String,
    val configuration: Map<String, Any>,
) {

    @JsonIgnore
    fun getDefaultContext(): Map<String, Any> {
        return configuration[PodConfigurationProperty.DEFAULT_CONTEXT]?.let { it as Map<String, Any> } ?: emptyMap()
    }

}

object PodConfigurationProperty {

    const val DEFAULT_CONTEXT = "defaultContext"

}

enum class PodEventType {
    CREATED,
    UPDATED,
    DELETED,
}

data class PodEvent(
    val type: PodEventType,
    val podId: String
)