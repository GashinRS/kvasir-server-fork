package kvasir.services.api.solid.impl

import io.smallrye.mutiny.Uni
import io.vertx.ext.web.RoutingContext
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.config.KvasirConfig
import kvasir.definitions.reactive.toMulti
import kvasir.definitions.reactive.toUni
import kvasir.services.api.solid.vocab.LDPVocab
import kvasir.utils.s3.S3Utils
import org.eclipse.microprofile.config.ConfigProvider
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.io.InputStream
import java.net.URI
import kotlin.jvm.optionals.getOrNull

internal const val METADATA_RESOURCE_SUFFIX = ".meta"

internal fun String.isContainerPath(): Boolean {
    return this.removeSuffix(METADATA_RESOURCE_SUFFIX).endsWith("/")
}

internal fun RoutingContext.getPodName(): String {
    return this.pathParam("podId")
}

internal fun RoutingContext.getPodId(): String {
    return "${getBaseUri()}/${this.getPodName()}"
}

internal fun RoutingContext.getBucketId(): String {
    return S3Utils.getBucket("${this.getBaseUri()}/${this.getPodName()}")
}

internal fun RoutingContext.getBaseUri(): String {
    return (ConfigProvider.getConfig().getOptionalValue(KvasirConfig.BASE_URI_PROPERTY, String::class.java).getOrNull()
        ?: KvasirConfig.BASE_URI_DEFAULT)
        .removeSuffix("/")
}

internal fun RoutingContext.getResourceUri(): URI {
    return URI.create("${getBaseUri()}${this.request().path()}")
}

internal fun RoutingContext.getRoot(): URI {
    return URI.create("${getBaseUri()}/${this.getPodName()}/solid/")
}

internal fun RoutingContext.isRoot(): Boolean {
    return this.request().path() == "/${this.pathParam("podId")}/solid/"
}

internal fun RoutingContext.isMetadataRequest(): Boolean {
    return this.getResourceUri().path.endsWith(METADATA_RESOURCE_SUFFIX)
}

internal fun RoutingContext.bodyAsInputStream(): InputStream {
    val buffer = this.body().buffer()
    return if (buffer != null && buffer.length() > 0) {
        buffer.bytes.inputStream()
    } else {
        InputStream.nullInputStream()
    }
}

internal fun S3AsyncClient.objectExists(bucket: String, key: String): Uni<Boolean> {
    return this.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).toUni()
        .map { true }.onFailure(NoSuchKeyException::class.java).recoverWithItem(false)
}

internal fun S3AsyncClient.objectsWithPrefixExist(bucket: String, prefix: String): Uni<Boolean> {
    return this.listObjectsV2Paginator { b ->
        b.bucket(bucket).prefix(prefix).maxKeys(1).delimiter("/")
    }.toMulti().collect().asList().map { results ->
        results.any { it.keyCount() > 0 }
    }
}

internal fun S3AsyncClient.prefixHasChildren(bucket: String, prefix: String): Uni<Boolean> {
    return this.listObjectsV2Paginator { b ->
        b.bucket(bucket).prefix(prefix).maxKeys(2).delimiter("/")
    }.toMulti().collect().asList().map { results ->
        results.flatMap { resp ->
            resp.contents().map { it.key() } + resp.commonPrefixes().map { it.prefix() }
        }.any { it != prefix }
    }
}

internal fun isSupportedContainerType(type: String?): Boolean {
    return type == LDPVocab.BasicContainer
}

internal fun MediaType.isRDFConvertableTo(other: MediaType): Boolean {
    return this.isSupportedRDFType() && other.isSupportedRDFType()
}

internal fun MediaType.isSupportedRDFType(): Boolean {
    return this.isCompatible(MediaType.valueOf("application/ld+json")) || this.isCompatible(MediaType.valueOf("text/turtle"))
}