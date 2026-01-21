package kvasir.definitions.annotations

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Persistent(
    val storageLevel: StorageLevel,
    val collectionName: String = NO_COLLECTION_SET,
    val modelVersion: String = "0"
) {
    companion object {
        const val NO_COLLECTION_SET = "##NO_COLLECTION_SET##"
    }
}

enum class StorageLevel {
    SYSTEM,
    PER_POD
}
