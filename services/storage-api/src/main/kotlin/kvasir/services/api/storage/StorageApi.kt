package kvasir.services.api.storage

import com.google.common.hash.Hashing
import io.quarkus.logging.Log
import io.quarkus.runtime.Startup
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.vertx.UniHelper
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.net.HostAndPort
import io.vertx.ext.web.Router
import io.vertx.httpproxy.*
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Instance
import kvasir.definitions.auth.AuthConstants
import kvasir.definitions.auth.AuthHandler
import kvasir.definitions.config.HttpConfig
import kvasir.definitions.storage.StorageEvent
import kvasir.definitions.storage.StorageEventType
import kvasir.plugins.messaging.kafka.StorageMutationEmitterProvider
import kvasir.plugins.storage.s3.S3StorageConfig
import kvasir.utils.s3.S3Utils
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
    private val s3Config: S3StorageConfig,
    private val s3Interceptor: S3Interceptor,
    private val authHandler: Instance<AuthHandler>
) {

    fun onStart(@Observes router: Router, vertx: Vertx) {
        Log.debug("storage-api proxying S3 requests to ${s3Config.endpoint()}")
        val s3Url = URI.create(s3Config.endpoint())
        val proxyClient = vertx.createHttpClient()
        val proxy = HttpProxy.reverseProxy(proxyClient)
        proxy.origin(s3Url.port, s3Url.host).addInterceptor(s3Interceptor)

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

@Startup
@ApplicationScoped
class S3Interceptor(
    private val config: HttpConfig,
    private val s3Config: S3StorageConfig,
    private val storageEventEmitterProvider: StorageMutationEmitterProvider,
    private val authHandler: Instance<AuthHandler>
) : ProxyInterceptor {

    companion object {

        private val ISO_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    }

    private val s3Url = URI.create(s3Config.endpoint())

    override fun handleProxyRequest(context: ProxyContext): Future<ProxyResponse> {
        val proxiedRequest = context.request().proxiedRequest()

        return if (proxiedRequest.isEnded) {
            Future.succeededFuture(Buffer.buffer())
        } else {
            proxiedRequest.resume().body()
        }.compose { buffer ->
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
                .awsCredentials(AwsCredentials(s3Config.accessKey(), s3Config.secretKey()))
                .header("host", "${s3Url.host}:${s3Url.port}")
                .header("x-amz-date", isoDateTime)
                .header("x-amz-content-sha256", payloadHash)
                .region("us-east-1") // TODO: Make configurable
                .buildS3(signUri, payloadHash)
                .signature
            context.request().putHeader("Authorization", sig)
            context.request().putHeader("x-amz-date", isoDateTime)
            context.request().putHeader("x-amz-content-sha256", payloadHash)
            context.request().putHeader("Host", "${s3Url.host}:${s3Url.port}")
            context.request().authority = HostAndPort.authority(s3Url.host, s3Url.port)
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
                    val bucket = sliceId?.let { S3Utils.getBucket("${config.baseUri()}$podId/slices/$it") }
                        ?: S3Utils.getBucket("${config.baseUri()}$podId")
                    val event = StorageEvent(
                        id = "urn:kvasir:storage-events:${UUID.randomUUID()}",
                        timestamp = Instant.now(),
                        requestingUser = authHandler.takeIf { it.isResolvable }?.get()
                            ?.getPrincipalForProxiedRequest(context.request().proxiedRequest())?.name
                            ?: AuthConstants.ANONYMOUS_USERNAME,
                        podId = "${config.baseUri()}$podId",
                        sliceId = sliceId?.let { "${config.baseUri()}$podId/slices/$it" },
                        objectId = URLDecoder.decode(
                            context.request().uri.substringAfter("/$bucket/").substringBefore("?"),
                            Charsets.UTF_8.name()
                        ),
                        externalObjectUri = "${config.baseUri().removeSuffix("/")}${
                            context.request().proxiedRequest().path()
                        }",
                        internalStorageUri = "${s3Config.endpoint()}${context.request().uri}",
                        versionId = context.response().headers().get("x-amz-version-id"),
                        eventType = operationType
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
            val bucketId = S3Utils.getBucket("${config.baseUri()}$podId/slices/$sliceId")
            uri.replaceFirst("/$podId/slices/$sliceId/s3", "/$bucketId")
        } else {
            val bucketId = S3Utils.getBucket("${config.baseUri()}$podId")
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