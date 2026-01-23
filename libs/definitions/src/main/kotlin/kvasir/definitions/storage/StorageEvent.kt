package kvasir.definitions.storage

import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.KvasirVocab
import java.time.Instant

@GenerateNoArgConstructor
data class StorageEvent(
    val context: JSONObject = KvasirVocab.context,
    val id: String,
    val requestingUser: String,
    val timestamp: Instant,
    val podId: String,
    val sliceId: String? = null,
    val objectId: String,
    val externalObjectUri: String,
    val internalStorageUri: String,
    val versionId: String? = null,
    val eventType: StorageEventType
)

enum class StorageEventType(val mutation: Boolean = false) {
    GET_OBJECT_METADATA,
    WRITE_OBJECT_METADATA,
    CREATE_OBJECT(true),
    GET_OBJECT,
    PUT_OBJECT(true),
    COMPLETE_MULTIPART_UPLOAD(true),
    RESTORE_OBJECT(true),
    DELETE_OBJECT(true),
}