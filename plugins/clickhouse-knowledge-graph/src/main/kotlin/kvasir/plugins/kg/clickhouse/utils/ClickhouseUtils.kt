package kvasir.plugins.kg.clickhouse.utils

import com.google.common.hash.Hashing
import kvasir.definitions.annotations.Persistent
import kvasir.definitions.annotations.StorageLevel
import kvasir.definitions.persistence.PersistentEntity
import java.time.Instant

internal const val INSERT_BUFFER = 5000
internal const val MAX_PAGE_SIZE_RECORDS = 25000
internal const val MAX_PAGE_SIZE_CHANGE_REPORTS = 250

object ClickhouseUtils {

    fun convertInstant(value: Instant): String {
        return value.toString().replace("T", " ").removeSuffix("Z")
    }

}

internal fun databaseFromPodId(podId: String): String {
    val dbName = Hashing.farmHashFingerprint64().hashString(podId, Charsets.UTF_8).toString()
    return dbName
}

internal fun parsePersistentAnnotation(entityClass: Class<out PersistentEntity>): ParsedPersistentAnnotation {
    val persistent = entityClass.getAnnotation(Persistent::class.java)
    return ParsedPersistentAnnotation(
        persistent.storageLevel,
        persistent.collectionName.takeIf { it != Persistent.NO_COLLECTION_SET }
            ?: "${entityClass.simpleName.lowercase()}s", persistent.modelVersion)
}

data class ParsedPersistentAnnotation(
    val storageLevel: StorageLevel,
    val collectionName: String,
    val modelVersion: String
)