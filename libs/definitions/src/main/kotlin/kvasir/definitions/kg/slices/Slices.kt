package kvasir.definitions.kg.slices

import com.fasterxml.jackson.annotation.JsonProperty
import io.smallrye.mutiny.Uni
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab

interface SliceStore {

    fun persist(segment: Slice): Uni<Void>

    fun list(podId: String): Uni<List<SliceSummary>>

    fun getById(podId: String, segmentId: String): Uni<Slice?>

    fun deleteById(podId: String, segmentId: String): Uni<Void>
}

data class Slice(
    @JsonProperty(JsonLdKeywords.id)
    val id: String,
    @JsonProperty(JsonLdKeywords.context)
    val context: Map<String, Any>,
    @JsonProperty(KvasirVocab.podId)
    val podId: String,
    @JsonProperty(KvasirVocab.name)
    val name: String,
    @JsonProperty(KvasirVocab.description)
    val description: String,
    @JsonProperty(KvasirVocab.schema)
    val schema: String,
    @JsonProperty(KvasirVocab.supportsChanges)
    val supportsChanges: Boolean = false,
    @JsonProperty(KvasirVocab.targetGraphs)
    val targetGraphs: Set<String> = emptySet()
)

data class SliceSummary(
    @JsonProperty(JsonLdKeywords.id)
    val id: String,
    @JsonProperty(KvasirVocab.name)
    val name: String,
    @JsonProperty(KvasirVocab.description)
    val description: String
)