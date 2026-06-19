package kvasir.services.api.storage

import com.google.common.hash.Hashing
import io.quarkus.logging.Log
import io.quarkus.runtime.Startup
import io.smallrye.mutiny.vertx.UniHelper
import io.smallrye.reactive.messaging.kafka.KafkaRecord
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.impl.HttpServerRequestWrapper
import io.vertx.core.net.HostAndPort
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
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
import kvasir.plugins.storage.s3.S3Utils
import uk.co.lucasweb.aws.v4.signer.Signer
import uk.co.lucasweb.aws.v4.signer.credentials.AwsCredentials
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.jvm.optionals.getOrNull

internal const val HEADER_X_AMZ_CONTENT_SHA256 = "x-amz-content-sha256"
internal const val HEADER_X_AMZ_DATE = "x-amz-date"
internal const val BODY_KEY = "body"

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
        Log.debug(
            "storage-api proxying S3 requests to ${s3Config.endpoint()} " +
                "(pool=${s3Config.proxyPoolSize()}, chunkSize=${s3Config.proxyMaxChunkSize()}, " +
                "rcvBuf=${s3Config.proxyReceiveBufferSize()}, sndBuf=${s3Config.proxySendBufferSize()})"
        )
        val s3Url = URI.create(s3Config.endpoint())
        val options = HttpClientOptions()
            .setMaxPoolSize(s3Config.proxyPoolSize())
            .setKeepAlive(true)
            .setPipelining(false)
        if (s3Config.proxyMaxChunkSize() > 0) options.setMaxChunkSize(s3Config.proxyMaxChunkSize())
        if (s3Config.proxyReceiveBufferSize() > 0) options.setReceiveBufferSize(s3Config.proxyReceiveBufferSize())
        if (s3Config.proxySendBufferSize() > 0) options.setSendBufferSize(s3Config.proxySendBufferSize())
        val proxyClient = vertx.createHttpClient(options)
        val proxy = HttpProxy.reverseProxy(proxyClient)
        proxy.origin(s3Url.port, s3Url.host).addInterceptor(s3Interceptor)

        // Write methods: buffer the full request body so the S3 interceptor can sign it.
        // If the client provides a pre-computed x-amz-content-sha256 header, skip body buffering
        // entirely so the proxy can stream the body through without loading it into memory.
        listOf(HttpMethod.PUT, HttpMethod.POST).forEach { method ->
            router.route(method, "/:podId/s3/*")
                .handler { ctx ->
                    if (ctx.request().getHeader(HEADER_X_AMZ_CONTENT_SHA256) != null) {
                        // Client provided content hash — skip body buffering for streaming
                        ctx.next()
                    } else {
                        BodyHandler.create().setBodyLimit(-1).handle(ctx)
                    }
                }
                .apply {
                    if (authHandler.isResolvable) {
                        this.handler(authHandler.get())
                    }
                }
                .handler { ctx ->
                    if (ctx.request().getHeader(HEADER_X_AMZ_CONTENT_SHA256) == null) {
                        ctx.body().buffer()?.let {
                            if (it.length() > 0) {
                                val reqContext = (ctx.request() as HttpServerRequestWrapper).context()
                                reqContext.putLocal(BODY_KEY, it)
                            }
                        }
                    }
                    proxy.handle(ctx.request())
                }
        }

        // Read / delete methods: skip BodyHandler entirely to avoid unnecessary buffering under load.
        listOf(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.DELETE).forEach { method ->
            router.route(method, "/:podId/s3/*")
                .apply {
                    if (authHandler.isResolvable) {
                        this.handler(authHandler.get())
                    }
                }
                .handler { ctx -> proxy.handle(ctx.request()) }
        }
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
        val method = context.request().method.name()
        val emptyBody = method in setOf("GET", "HEAD", "DELETE")
        val clientProvidedHash = context.request().headers().get(HEADER_X_AMZ_CONTENT_SHA256)

        // When the client provides x-amz-content-sha256, skip body buffering — the proxy
        // streams the body directly to S3. Otherwise, read the buffered body for hashing.
        val buffer: Buffer? = if (clientProvidedHash != null && !emptyBody) {
            // Body is being streamed; do not override it
            null
        } else if (emptyBody) {
            Buffer.buffer()
        } else {
            (proxiedRequest as HttpServerRequestWrapper).context().getLocal<Buffer?>(BODY_KEY) ?: Buffer.buffer()
        }

        if (buffer != null) {
            context.request().body = Body.body(buffer)
        }
        val podId = context.request().proxiedRequest().getParam("podId")
        val sliceId = context.request().proxiedRequest().getParam("sliceId")
        val target = replacePath(context.request().uri, podId, sliceId)
        context.request().uri = target
        val isoDateTime = getIsoDateTime(context)
        val payloadHash = when {
            emptyBody -> "UNSIGNED-PAYLOAD"
            clientProvidedHash != null -> clientProvidedHash
            else -> getPayloadHash(context, buffer!!)
        }
        val targetUri = URI.create(target);
        val targetDecoded = arrayOf(targetUri.path, targetUri.query ?: "").joinToString("?");
        val signUri = uk.co.lucasweb.aws.v4.signer.HttpRequest(context.request().method.name(), targetDecoded)
        val hostHeader = if (s3Url.port == -1 || s3Url.port in listOf(80, 443)) {
            s3Url.host
        } else {
            "${s3Url.host}:${s3Url.port}"
        }
        val sig = Signer.builder()
            .awsCredentials(AwsCredentials(s3Config.accessKey(), s3Config.secretKey()))
            .header("host", hostHeader)
            .header("x-amz-date", isoDateTime)
            .header("x-amz-content-sha256", payloadHash)
            .region(s3Config.region())
            .buildS3(signUri, payloadHash)
            .signature
        context.request().putHeader("Authorization", sig)
        context.request().putHeader("x-amz-date", isoDateTime)
        context.request().putHeader("x-amz-content-sha256", payloadHash)
        context.request().putHeader("Host", hostHeader)
        context.request().authority = HostAndPort.authority(s3Url.host, s3Url.port)
        return context.sendRequest()
    }

    override fun handleProxyResponse(context: ProxyContext): Future<Void> {
        val resp = context.response()
        // Hack to remove access-control-allow-origin header that Minio handler internally puts here
        resp.headers().remove("access-control-allow-origin")
        val operationType = determineOperationType(context)
        return if (resp.statusCode in 200..399 && operationType != null && s3Config.publishEventTypes().getOrNull()
                ?.contains(operationType) == true
        ) {
            context.sendResponse().compose {
                UniHelper.toFuture(
                    run {
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
                        storageEventEmitterProvider.getEmitter().sendMessage(KafkaRecord.of(podId, event))
                            .replaceWithVoid()
                    }
                )
            }
        } else {
            context.sendResponse()
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