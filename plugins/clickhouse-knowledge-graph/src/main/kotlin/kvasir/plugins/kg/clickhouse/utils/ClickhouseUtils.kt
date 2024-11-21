package kvasir.plugins.kg.clickhouse.utils

import java.time.Instant

object ClickhouseUtils {

    fun convertInstant(value: Instant): String {
        return value.toString().replace("T", " ").removeSuffix("Z")
    }

}