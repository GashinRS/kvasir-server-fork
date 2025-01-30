package kvasir.plugins.kg.clickhouse.utils

import com.google.common.hash.Hashing
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import kvasir.definitions.kg.ChangeRecord
import kvasir.definitions.kg.ChangeRecordRequest
import kvasir.definitions.kg.PagedResult
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
    return Hashing.farmHashFingerprint64().hashString(podId, Charsets.UTF_8).toString()
}