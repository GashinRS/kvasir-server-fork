package kvasir.services.api.storage

import com.google.common.hash.Hashing
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.vertx.UniHelper
import io.smallrye.reactive.messaging.MutinyEmitter
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.net.HostAndPort
import io.vertx.ext.web.Router
import io.vertx.httpproxy.*
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import kvasir.definitions.messaging.Channels
import kvasir.definitions.storage.StorageMutationEvent
import kvasir.definitions.storage.StorageMutationEventType
import kvasir.utils.s3.S3Utils
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import uk.co.lucasweb.aws.v4.signer.Signer
import uk.co.lucasweb.aws.v4.signer.credentials.AwsCredentials
import java.net.URI
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal const val HEADER_X_AMZ_CONTENT_SHA256 = "x-amz-content-sha256"
internal const val HEADER_X_AMZ_DATE = "x-amz-date"

/**
 * Proxy for an S3 backend.
 * This proxy allows accessing the S3 backend using Kvasir's authentication and authorization mechanisms.
 * In addition, successful write requests are published to the message bus for further processing.
 */
@ApplicationScoped
class StorageApi(
    @ConfigProperty(name = "kvasir.services.storage.s3.host", defaultValue = "localhost")
    private val s3Host: String,
    @ConfigProperty(name = "kvasir.services.storage.s3.port", defaultValue = "9000")
    private val s3Port: Int,
    private val s3Interceptor: S3Interceptor
) {

    fun onStart(@Observes router: Router, vertx: Vertx) {
        val proxyClient = vertx.createHttpClient()
        val proxy = HttpProxy.reverseProxy(proxyClient)
        proxy.origin(s3Port, s3Host).addInterceptor(s3Interceptor)

        router.route("/:podId/s3/*").handler { ctx ->
            proxy.handle(ctx.request())
        }
// Disable Slice-specific S3 for now
//        router.route("/:podId/slices/:sliceId/s3/*").handler { ctx ->
//            proxy.handle(ctx.request())
//        }
    }

}

@ApplicationScoped
class S3Interceptor(
    @ConfigProperty(name = "kvasir.base-uri", defaultValue = "http://localhost:8080/")
    private val baseUri: String,
    @ConfigProperty(name = "kvasir.services.storage.s3.host")
    private val s3Host: String,
    @ConfigProperty(name = "kvasir.services.storage.s3.port")
    private val s3Port: Int,
    @ConfigProperty(name = "kvasir.services.storage.s3.access-key")
    private val s3AccessKey: String,
    @ConfigProperty(name = "kvasir.services.storage.s3.secret-key")
    private val s3SecretKey: String,
    private val storageMutationEmitterProvider: StorageMutationEmitterProvider,
) : ProxyInterceptor {

    companion object {

        private val ISO_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    }

    override fun handleProxyRequest(context: ProxyContext): Future<ProxyResponse> {
        return context.request().proxiedRequest().resume().body().compose { buffer ->
            context.request().body = Body.body(buffer)
            val podId = context.request().proxiedRequest().getParam("podId")
            val sliceId = context.request().proxiedRequest().getParam("sliceId")
            val target = replacePath(context.request().uri, podId, sliceId)
            context.request().uri = target
            val isoDateTime = getIsoDateTime(context)
            val payloadHash = getPayloadHash(context, buffer)
            val targetUri = URI.create(target);
            val targetDecoded = arrayOf(targetUri.path, targetUri.query ?: "").joinToString("?");
            val signUri = uk.co.lucasweb.aws.v4.signer.HttpRequest(context.request().method.name(), targetDecoded)
            val sig = Signer.builder()
                .awsCredentials(AwsCredentials(s3AccessKey, s3SecretKey))
                .header("host", "$s3Host:$s3Port")
                .header("x-amz-date", isoDateTime)
                .header("x-amz-content-sha256", payloadHash)
                .region("us-east-1") // TODO: Make configurable
                .buildS3(signUri, payloadHash)
                .signature
            context.request().putHeader("Authorization", sig)
            context.request().putHeader("x-amz-date", isoDateTime)
            context.request().putHeader("x-amz-content-sha256", payloadHash)
            context.request().putHeader("Host", "$s3Host:$s3Port")
            context.request().authority = HostAndPort.authority(s3Host, s3Port)
            context.sendRequest()
        }
    }

    override fun handleProxyResponse(context: ProxyContext): Future<Void> {
        val resp = context.response()
        // Hack to remove access-control-allow-origin header that Minio handler internally puts here
        resp.headers().remove("access-control-allow-origin")
        val mutationType = determineMutationType(context)
        return context.sendResponse().compose {
            UniHelper.toFuture(
                if (resp.statusCode in 200..399 && mutationType != null) {
                    val podId = context.request().proxiedRequest().getParam("podId")
                    val sliceId = context.request().proxiedRequest().getParam("sliceId")
                    val bucket = sliceId?.let { S3Utils.getBucket("$baseUri$podId/slices/$it") }
                        ?: S3Utils.getBucket("$baseUri$podId")
                    val event = StorageMutationEvent(
                        podId = "$baseUri$podId",
                        sliceId = sliceId?.let { "$baseUri$podId/slices/$it" },
                        objectId = context.request().uri.substringAfter("/$bucket/").substringBefore("?"),
                        externalObjectUri = context.request().proxiedRequest().absoluteURI(),
                        internalStorageUri = "http://$s3Host:$s3Port${context.request().uri}",
                        versionId = context.response().headers().get("x-amz-version-id"),
                        mutationType = mutationType
                    )
                    storageMutationEmitterProvider.getEmitter().send(event).replaceWithVoid()
                } else {
                    Uni.createFrom().voidItem()
                }
            )
        }
    }

    private fun getIsoDateTime(context: ProxyContext): String {
        return context.request().headers().get(HEADER_X_AMZ_DATE)
            ?: ISO_DATE_FORMATTER.format(ZonedDateTime.now(ZoneOffset.UTC))
    }

    private fun getPayloadHash(context: ProxyContext, body: Buffer): String {
        return context.request().headers().get(HEADER_X_AMZ_CONTENT_SHA256)
            ?: Hashing.sha256().hashBytes(body.bytes).toString()
    }

    // TODO: take into account potential API prefixes
    private fun replacePath(uri: String, podId: String, sliceId: String?): String {
        return if (sliceId != null) {
            val bucketId = S3Utils.getBucket("$baseUri$podId/slices/$sliceId")
            uri.replaceFirst("/$podId/slices/$sliceId/s3", "/$bucketId")
        } else {
            val bucketId = S3Utils.getBucket("$baseUri$podId")
            uri.replaceFirst("/$podId/s3", "/$bucketId")
        }
    }

    // TODO: Implement the determineMutationType method properly
    private fun determineMutationType(context: ProxyContext): StorageMutationEventType? {
        val method = context.request().method.name()
        return when {
            method == "PUT" -> StorageMutationEventType.PUT_OBJECT
            method == "DELETE" -> StorageMutationEventType.DELETE_OBJECT
            method == "POST" && context.request().proxiedRequest().params()
                .contains("uploadId") -> StorageMutationEventType.COMPLETE_MULTIPART_UPLOAD

            else -> null
        }
    }

}

@ApplicationScoped
class StorageMutationEmitterProvider(
    @Channel(Channels.STORAGE_MUTATIONS_PUBLISH)
    private val storageMutationsEmitter: MutinyEmitter<StorageMutationEvent>
) {
    fun getEmitter(): MutinyEmitter<StorageMutationEvent> {
        return storageMutationsEmitter
    }
}