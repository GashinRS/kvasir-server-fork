package kvasir.utils.s3

import com.google.common.hash.Hashing

object S3Utils {

    fun getBucket(podOrSliceUri: String): String {
        return Hashing.farmHashFingerprint64().hashString(podOrSliceUri, Charsets.UTF_8).toString()
    }

}