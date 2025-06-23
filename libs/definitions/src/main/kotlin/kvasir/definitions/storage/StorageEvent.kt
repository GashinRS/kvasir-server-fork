package kvasir.definitions.storage

import com.fasterxml.jackson.annotation.JsonProperty
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import java.time.Instant

@GenerateNoArgConstructor
data class StorageEvent(
    @get:JsonProperty(JsonLdKeywords.id)
    val id: String,
    @get:JsonProperty(KvasirVocab.timestamp)
    val timestamp: Instant,
    @get:JsonProperty(KvasirVocab.podId)
    val podId: String,
    @get:JsonProperty(KvasirVocab.sliceId)
    val sliceId: String? = null,
    @get:JsonProperty(KvasirVocab.objectId)
    val objectId: String,
    @get:JsonProperty(KvasirVocab.externalObjectUri)
    val externalObjectUri: String,
    @get:JsonProperty(KvasirVocab.internalObjectUri)
    val internalStorageUri: String,
    @get:JsonProperty(KvasirVocab.versionId)
    val versionId: String,
    @get:JsonProperty(KvasirVocab.type)
    val type: StorageEventType
)

enum class StorageEventType(val mutation: Boolean = false) {
    GET_OBJECT_METADATA,
    GET_OBJECT,
    PUT_OBJECT(true),
    COMPLETE_MULTIPART_UPLOAD(true),
    RESTORE_OBJECT(true),
    DELETE_OBJECT(true),
}