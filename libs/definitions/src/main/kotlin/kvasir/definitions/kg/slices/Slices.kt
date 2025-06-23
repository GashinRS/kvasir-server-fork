package kvasir.definitions.kg.slices

import com.fasterxml.jackson.annotation.JsonProperty
import io.smallrye.mutiny.Uni
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab

interface SliceStore {

    fun persist(segment: Slice): Uni<Void>

    fun list(podId: String): Uni<List<SliceSummary>>

    fun getById(podId: String, segmentId: String): Uni<Slice?>

    fun deleteById(podId: String, segmentId: String): Uni<Void>
}

@GenerateNoArgConstructor
data class Slice(
    @get:JsonProperty(JsonLdKeywords.id)
    val id: String,
    @get:JsonProperty(JsonLdKeywords.context)
    val context: Map<String, Any>,
    @get:JsonProperty(KvasirVocab.podId)
    val podId: String,
    @get:JsonProperty(KvasirVocab.name)
    val name: String,
    @get:JsonProperty(KvasirVocab.description)
    val description: String,
    @get:JsonProperty(KvasirVocab.schema)
    val schema: String,
    @get:JsonProperty(KvasirVocab.supportsChanges)
    val supportsChanges: Boolean = false,
    @get:JsonProperty(KvasirVocab.targetGraphs)
    val targetGraphs: Set<String> = emptySet()
)

data class SliceSummary(
    @get:JsonProperty(JsonLdKeywords.id)
    val id: String,
    @get:JsonProperty(KvasirVocab.name)
    val name: String,
    @get:JsonProperty(KvasirVocab.description)
    val description: String
)