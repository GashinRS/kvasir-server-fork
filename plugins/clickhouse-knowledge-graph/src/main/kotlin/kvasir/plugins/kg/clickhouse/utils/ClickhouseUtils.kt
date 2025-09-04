package kvasir.plugins.kg.clickhouse.utils

import com.google.common.base.CaseFormat
import com.google.common.hash.Hashing
import io.vertx.core.json.Json
import kvasir.definitions.persistence.PersistentEntity
import java.time.Instant
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties

internal const val INSERT_BUFFER = 5000
internal const val MAX_PAGE_SIZE_RECORDS = 25000
internal const val MAX_PAGE_SIZE_CHANGE_REPORTS = 250

object ClickhouseUtils {

    fun convertInstant(value: Instant): String {
        return value.toString().replace("T", " ").removeSuffix("Z")
    }

}

internal fun databaseFromPodId(podId: String): String {
    return Hashing.farmHashFingerprint64().hashString(podId, Charsets.UTF_8).toString()
}

internal fun toSnakeCase(fieldName: String): String {
    return CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, fieldName)
}

internal fun <T : PersistentEntity> getColumnNames(entityClass: KClass<T>): List<String> {
    return listOf("id") + entityClass.memberProperties.filterNot { it.name == "id" }.map { toSnakeCase(it.name) }
        .sorted()
}