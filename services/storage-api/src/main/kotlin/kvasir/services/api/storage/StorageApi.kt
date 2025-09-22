package kvasir.services.api.storage

import com.google.common.hash.Hashing
import io.quarkus.logging.Log
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
import jakarta.enterprise.inject.Instance
import kvasir.definitions.auth.AuthHandler
import kvasir.definitions.config.KvasirConfig
import kvasir.definitions.storage.StorageEvent
import kvasir.definitions.storage.StorageEventType
import kvasir.plugins.messaging.kafka.Channels
import kvasir.utils.s3.S3Utils
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import uk.co.lucasweb.aws.v4.signer.Signer
import uk.co.lucasweb.aws.v4.signer.credentials.AwsCredentials
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*

internal const val HEADER_X_AMZ_CONTENT_SHA256 = "x-amz-content-sha256"
internal const val HEADER_X_AMZ_DATE = "x-amz-date"

/**
 * Proxy for an S3 backend.
 * This proxy allows accessing the S3 backend using Kvasir's authentication and authorization mechanisms.
 * In addition, successful write requests are published to the message bus for further processing.
 */
@ApplicationScoped
class StorageApi(
    @ConfigProperty(name = "kvasir.services.storage.s3.host")
    private val s3Host: String,
    @ConfigProperty(name = "kvasir.services.storage.s3.port")
    private val s3Port: Int,
    @ConfigProperty(name = "quarkus.minio.url")
    private val minioHost: String,
    private val s3Interceptor: S3Interceptor,
    private val authHandler: Instance<AuthHandler>
) {

    fun onStart(@Observes router: Router, vertx: Vertx) {
        Log.debug("storage-api sees '$minioHost' as minio host")
        Log.debug("storage-api proxying S3 requests to $s3Host:$s3Port")
        val proxyClient = vertx.createHttpClient()
        val proxy = HttpProxy.reverseProxy(proxyClient)
        proxy.origin(s3Port, s3Host).addInterceptor(s3Interceptor)

        val setupRoute = {
            if (authHandler.isResolvable) {
                router.route("/:podId/s3/*")
                    .handler(authHandler.get())
            } else {
                router.route("/:podId/s3/*")
            }
        }
        setupRoute().handler { ctx -> proxy.handle(ctx.request()) }
    }

}

@ApplicationScoped
class S3Interceptor(
    @ConfigProperty(name = KvasirConfig.BASE_URI_PROPERTY, defaultValue = KvasirConfig.BASE_URI_DEFAULT)
    private val baseUri: String,
    @ConfigProperty(name = "kvasir.services.storage.s3.host")
    private val s3Host: String,
    @ConfigProperty(name = "kvasir.services.storage.s3.port")
    private val s3Port: Int,
    @ConfigProperty(name = "kvasir.services.storage.s3.access-key")
    private val s3AccessKey: String,
    @ConfigProperty(name = "kvasir.services.storage.s3.secret-key")
    private val s3SecretKey: String,
    @ConfigProperty(name = "kvasir.auth.anonymous-user-name", defaultValue = "anonymous")
    private val anonymousUserName: String,
    private val storageEventEmitterProvider: StorageMutationEmitterProvider,
    private val authHandler: Instance<AuthHandler>
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
        val operationType = determineOperationType(context)
        return context.sendResponse().compose {
            UniHelper.toFuture(
                if (resp.statusCode in 200..399 && operationType != null) {
                    val podId = context.request().proxiedRequest().getParam("podId")
                    val sliceId = context.request().proxiedRequest().getParam("sliceId")
                    val bucket = sliceId?.let { S3Utils.getBucket("$baseUri$podId/slices/$it") }
                        ?: S3Utils.getBucket("$baseUri$podId")
                    val event = StorageEvent(
                        id = "urn:kvasir:storage-events:${UUID.randomUUID()}",
                        timestamp = Instant.now(),
                        requestingUser = authHandler.takeIf { it.isResolvable }?.get()
                            ?.getPrincipalForProxiedRequest(context.request().proxiedRequest())?.name
                            ?: anonymousUserName,
                        podId = "$baseUri$podId",
                        sliceId = sliceId?.let { "$baseUri$podId/slices/$it" },
                        objectId = URLDecoder.decode(
                            context.request().uri.substringAfter("/$bucket/").substringBefore("?"),
                            Charsets.UTF_8.name()
                        ),
                        externalObjectUri = "${baseUri.removeSuffix("/")}${context.request().proxiedRequest().path()}",
                        internalStorageUri = "http://$s3Host:$s3Port${context.request().uri}",
                        versionId = context.response().headers().get("x-amz-version-id"),
                        type = operationType
                    )
                    storageEventEmitterProvider.getEmitter().send(event).replaceWithVoid()
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
    private fun determineOperationType(context: ProxyContext): StorageEventType? {
        val method = context.request().method.name()
        return when {
            method == "GET" -> StorageEventType.GET_OBJECT
            method == "HEAD" -> StorageEventType.GET_OBJECT_METADATA
            method == "PUT" -> StorageEventType.PUT_OBJECT
            method == "DELETE" -> StorageEventType.DELETE_OBJECT
            method == "POST" && context.request().proxiedRequest().params()
                .contains("uploadId") -> StorageEventType.COMPLETE_MULTIPART_UPLOAD

            else -> null
        }
    }

}

@ApplicationScoped
class StorageMutationEmitterProvider(
    @Channel(Channels.STORAGE_EVENTS_PUBLISH)
    private val storageMutationsEmitter: MutinyEmitter<StorageEvent>
) {
    fun getEmitter(): MutinyEmitter<StorageEvent> {
        return storageMutationsEmitter
    }
}