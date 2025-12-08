package kvasir.plugins.storage.s3

import io.smallrye.config.ConfigMapping

@ConfigMapping(prefix = "kvasir.storage.s3")
interface S3StorageConfig {
    fun endpoint(): String
    fun accessKey(): String
    fun secretKey(): String
    fun region(): String
}