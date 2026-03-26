package kvasir.plugins.storage.s3

import com.google.common.hash.Hashing
import io.smallrye.mutiny.Uni
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest

object S3Utils {

    fun getBucket(podOrSliceUri: String): String {
        return Hashing.farmHashFingerprint64().hashString(podOrSliceUri, Charsets.UTF_8).toString()
    }

}

fun S3AsyncClient.getObject(
    bucketId: String,
    key: String,
    versionId: String? = null
): Uni<TypedGetObjectResponseStream> {
    return Uni.combine().all().unis(
        Uni.createFrom()
            .future(
                this.getObject(
                    GetObjectRequest.builder().bucket(bucketId).key(key).apply {
                        if (versionId != null) {
                            this.versionId(versionId)
                        }
                    }.build(), AsyncResponseTransformer.toBlockingInputStream<GetObjectResponse>()
                )
            ),
        this.getObjectContentType(bucketId, key, versionId)
    )
        .asTuple()
        .map { resp -> TypedGetObjectResponseStream(resp.item1, resp.item2) }
}

fun S3AsyncClient.getObjectContentType(bucketId: String, key: String, versionId: String? = null): Uni<String> {
    return Uni.createFrom().future(
        this.headObject(
            HeadObjectRequest.builder().bucket(bucketId).key(key).apply {
                if (versionId != null) {
                    this.versionId(versionId)
                }
            }.build()
        )
    ).map { resp -> resp.contentType() }
}

data class TypedGetObjectResponseStream(
    val inputStream: ResponseInputStream<GetObjectResponse>,
    val contentType: String
)