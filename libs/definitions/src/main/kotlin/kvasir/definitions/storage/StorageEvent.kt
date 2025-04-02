package kvasir.definitions.storage

import com.fasterxml.jackson.annotation.JsonProperty
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import java.time.Instant

data class StorageEvent(
    @JsonProperty(JsonLdKeywords.id)
    val id: String,
    @JsonProperty(KvasirVocab.timestamp)
    val timestamp: Instant,
    @JsonProperty(KvasirVocab.podId)
    val podId: String,
    @JsonProperty(KvasirVocab.sliceId)
    val sliceId: String? = null,
    @JsonProperty(KvasirVocab.objectId)
    val objectId: String,
    @JsonProperty(KvasirVocab.externalObjectUri)
    val externalObjectUri: String,
    @JsonProperty(KvasirVocab.internalObjectUri)
    val internalStorageUri: String,
    @JsonProperty(KvasirVocab.versionId)
    val versionId: String,
    @JsonProperty(KvasirVocab.type)
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