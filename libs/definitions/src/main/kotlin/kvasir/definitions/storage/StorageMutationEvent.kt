package kvasir.definitions.storage

data class StorageMutationEvent(
    val podId: String,
    val sliceId: String? = null,
    val objectId: String,
    val externalObjectUri: String,
    val internalStorageUri: String,
    val versionId: String,
    val mutationType: StorageMutationEventType
)

enum class StorageMutationEventType {
    PUT_OBJECT,
    COMPLETE_MULTIPART_UPLOAD,
    RESTORE_OBJECT,
    DELETE_OBJECT,
}