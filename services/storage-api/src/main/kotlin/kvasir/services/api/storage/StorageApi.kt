package kvasir.services.api.storage

import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.vertx.UniHelper
import io.smallrye.reactive.messaging.MutinyEmitter
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.Router
import io.vertx.httpproxy.HttpProxy
import io.vertx.httpproxy.ProxyContext
import io.vertx.httpproxy.ProxyInterceptor
import io.vertx.httpproxy.ProxyResponse
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kvasir.definitions.storage.StorageMutationEvent
import kvasir.definitions.storage.StorageMutationEventType
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import uk.co.lucasweb.aws.v4.signer.Signer
import uk.co.lucasweb.aws.v4.signer.credentials.AwsCredentials
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

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
    }

}

@ApplicationScoped
class S3Interceptor(
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
        private val AMZ_DATE_HEADER = "x-amz-date"
        private val AMZ_CONTENT_SHA_HEADER = "x-amz-content-sha256"
        private val EMPTY_PAYLOAD_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    }

    override fun handleProxyRequest(context: ProxyContext): Future<ProxyResponse> {
        val podId = context.request().proxiedRequest().getParam("podId")
        val target = replacePath(context.request().uri, podId)
        context.request().setURI(target)
        val isoDateTime = getIsoDateTime(context)
        val payloadHash = getPayloadHash(context)
        val signUri = uk.co.lucasweb.aws.v4.signer.HttpRequest(context.request().method.name(), target)
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
        return context.sendRequest()
    }

    override fun handleProxyResponse(context: ProxyContext): Future<Void> {
        val resp = context.response()
        val mutationType = determineMutationType(context)
        return context.sendResponse().compose {
            UniHelper.toFuture(
                if (resp.statusCode in 200..399 && mutationType != null) {
                    val podId = context.request().proxiedRequest().getParam("podId")
                    val event = StorageMutationEvent(
                        podId = podId,
                        objectId = context.request().uri.substringAfter("/$podId/"),
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
        return context.request().headers().get(AMZ_DATE_HEADER)
            ?: ISO_DATE_FORMATTER.format(ZonedDateTime.now(ZoneOffset.UTC))
    }

    private fun getPayloadHash(context: ProxyContext): String {
        return context.request().headers().get(AMZ_CONTENT_SHA_HEADER)
            ?: if (context.request().method.name() == HttpMethod.GET.name()) EMPTY_PAYLOAD_HASH else throw IllegalArgumentException(
                "Missing required header: $AMZ_CONTENT_SHA_HEADER"
            )
    }

    // TODO: take into account potential API prefixes
    private fun replacePath(uri: String, podId: String): String {
        return uri.replaceFirst("/$podId/s3", "/$podId")
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
    @Channel("storage_mutations_publish")
    private val storageMutationsEmitter: MutinyEmitter<StorageMutationEvent>
) {
    fun getEmitter(): MutinyEmitter<StorageMutationEvent> {
        return storageMutationsEmitter
    }
}