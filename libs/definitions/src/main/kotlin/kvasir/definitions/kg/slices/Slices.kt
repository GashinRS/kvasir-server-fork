package kvasir.definitions.kg.slices

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import kvasir.definitions.annotations.Persistent
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.annotations.StorageLevel
import kvasir.definitions.persistence.PersistentEntity

@GenerateNoArgConstructor
@Persistent(storageLevel = StorageLevel.PER_POD, collectionName = "slices")
@JsonIgnoreProperties(ignoreUnknown = true)
data class Slice(
    override var id: String,
    var context: Map<String, Any>,
    var author: String,
    var name: String,
    var description: String,
    var schema: String,
    var supportsChanges: Boolean = false,
    var targetGraphs: Set<String> = emptySet()
) : PersistentEntity()

data class SliceSummary(
    val id: String,
    val name: String,
    val description: String
)