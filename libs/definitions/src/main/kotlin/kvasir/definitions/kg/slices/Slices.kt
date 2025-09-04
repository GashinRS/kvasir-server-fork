package kvasir.definitions.kg.slices

import com.fasterxml.jackson.annotation.JsonProperty
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.persistence.PersistentEntity
import kvasir.definitions.persistence.Repository
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab

interface SliceStoreFactory {
    fun getSliceStore(podId: String): SliceStore
}

interface SliceStore : Repository<Slice>

@GenerateNoArgConstructor
data class Slice(
    @get:JsonProperty(JsonLdKeywords.id)
    override var id: String,
    @get:JsonProperty(JsonLdKeywords.context)
    var context: Map<String, Any>,
    @get:JsonProperty(KvasirVocab.author)
    var author: String,
    @get:JsonProperty(KvasirVocab.name)
    var name: String,
    @get:JsonProperty(KvasirVocab.description)
    var description: String,
    @get:JsonProperty(KvasirVocab.schema)
    var schema: String,
    @get:JsonProperty(KvasirVocab.supportsChanges)
    var supportsChanges: Boolean = false,
    @get:JsonProperty(KvasirVocab.targetGraphs)
    var targetGraphs: Set<String> = emptySet()
) : PersistentEntity()

data class SliceSummary(
    @get:JsonProperty(JsonLdKeywords.id)
    val id: String,
    @get:JsonProperty(KvasirVocab.name)
    val name: String,
    @get:JsonProperty(KvasirVocab.description)
    val description: String
)