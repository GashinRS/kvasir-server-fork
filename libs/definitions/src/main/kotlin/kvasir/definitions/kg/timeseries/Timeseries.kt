package kvasir.definitions.kg.timeseries

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.jsonldjava.shaded.com.google.common.hash.Hashing
import kvasir.definitions.annotations.GenerateNoArgConstructor
import java.time.Instant

@GenerateNoArgConstructor
data class Observation(
    val id: String,
    val changeRequestId: String,
    val changeRequestTimestamp: Instant,
    var timestamp: Instant,
    var labels: Map<String, String> = emptyMap(),
    var value: Any,
    val dataType: String? = null,
    val language: String? = null,
    // Current limitation: a single observation must exist in one specific graph!
    val graph: String? = null,
) {
    val series: String
        @JsonProperty("series", access = JsonProperty.Access.READ_ONLY)
        get() = labelsToSeriesId(labels)
}

fun labelsToSeriesId(labels: Map<String, String>): String {
    return Hashing.farmHashFingerprint64().hashString(
        labels.toSortedMap().entries.joinToString(separator = ",") { "${it.key}=${it.value}" },
        Charsets.UTF_8
    ).asLong().toULong().toString()
}