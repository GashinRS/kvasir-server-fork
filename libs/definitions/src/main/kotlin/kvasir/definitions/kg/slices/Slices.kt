package kvasir.definitions.kg.slices

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.annotations.Persistent
import kvasir.definitions.annotations.StorageLevel
import kvasir.definitions.persistence.PersistentEntity
import kvasir.definitions.persistence.RevisionLineage
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab

@GenerateNoArgConstructor
@Persistent(storageLevel = StorageLevel.PER_POD, collectionName = "slices", versioned = true)
@JsonIgnoreProperties(ignoreUnknown = true)
data class Slice(
    override var id: String,
    override var createdBy: String,
    var context: Map<String, Any>,
    var name: String,
    var description: String,
    var schema: SliceSchema,
    var supportsChanges: Boolean = false
) : PersistentEntity()

data class SliceSummary(
    val id: String,
    val name: String,
    val description: String,
    val lineage: RevisionLineage? = null
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = JsonLdKeywords.type)
@JsonSubTypes(
    JsonSubTypes.Type(value = EmbeddedSliceSchema::class, name = KvasirVocab.EmbeddedSliceSchema),
    JsonSubTypes.Type(value = ExternalSliceSchema::class, name = KvasirVocab.ExternalSliceSchema)
)
interface SliceSchema

@GenerateNoArgConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
data class EmbeddedSliceSchema(val sdl: String) : SliceSchema

@GenerateNoArgConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
data class ExternalSliceSchema(val ref: String) : SliceSchema

/**
 * Temporary utility functions that tries to use a generic SliceSchema as EmbeddedSliceSchema to fetch the SDL.
 */
fun SliceSchema.tryReadingEmbeddedSDL(): String {
    return when (this) {
        is EmbeddedSliceSchema -> this.sdl
        else -> throw RuntimeException("Cannot read SDL from '${this::class.simpleName}', only 'EmbeddedSliceSchema' is supported at this moment.'")
    }
}