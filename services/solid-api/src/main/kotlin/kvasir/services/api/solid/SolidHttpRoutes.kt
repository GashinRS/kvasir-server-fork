package kvasir.services.api.solid

import com.github.jsonldjava.shaded.com.google.common.hash.Hashing
import io.quarkus.arc.All
import io.quarkus.logging.Log
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.vertx.core.MultiMap
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.mutiny.core.Vertx
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Instance
import jakarta.inject.Singleton
import jakarta.ws.rs.ClientErrorException
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedHashMap
import kvasir.definitions.auth.AuthConstants
import kvasir.definitions.auth.AuthHandler
import kvasir.definitions.reactive.skipToLast
import kvasir.definitions.reactive.toUni
import kvasir.definitions.storage.StorageEvent
import kvasir.definitions.storage.StorageEventType
import kvasir.plugins.messaging.kafka.StorageMutationEmitterProvider
import kvasir.plugins.storage.s3.S3StorageConfig
import kvasir.plugins.storage.s3.S3Utils
import kvasir.services.api.solid.impl.bodyAsInputStream
import kvasir.services.api.solid.impl.isRDFConvertableTo
import kvasir.services.api.solid.impl.isSupportedContainerType
import kvasir.services.api.solid.vocab.LDPVocab
import kvasir.services.api.solid.vocab.PIMVocab
import org.apache.commons.lang3.StringUtils
import org.eclipse.rdf4j.rio.Rio
import org.jboss.resteasy.reactive.common.headers.LinkHeaders
import org.jboss.resteasy.reactive.server.jaxrs.HttpHeadersImpl
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.util.*
import java.util.function.Supplier

const val PATH_REGEX = "\\/(?<podId>[^\\/]+)\\/solid\\/.*"
const val NEW_DOC_KEY = "kvasir.solid.new-document-key"

@Singleton
class SolidHttpRoutes(
    private val solidApi: SolidApi,
    private val authHandler: Instance<AuthHandler>,
    @All
    private val patchParsers: MutableList<PatchParser>,
    private val vertx: Vertx,
    private val storageMutationsEmitter: StorageMutationEmitterProvider,
    private val s3Config: S3StorageConfig,
    private val solidResourceRequestContextProvider: SolidResourceRequestContextProvider
) {

    fun init(@Observes router: Router) {
        Log.debug("Setting up Solid HTTP routes")
        try {
            router
                .routeWithRegex(PATH_REGEX)
                .handler(BodyHandler.create())
                .apply {
                    if (authHandler.isResolvable) {
                        this.handler(authHandler.get())
                    }
                }
                .consumes(MediaType.WILDCARD)
                .produces(MediaType.WILDCARD)
                .handler(::handler)
        } catch (t: Throwable) {
            Log.error("Error setting up Solid HTTP routes", t)
            throw t
        }
    }

    fun handler(ctx: RoutingContext) {
        val solidRequestContext = solidResourceRequestContextProvider.getContext(ctx)
        // Add Vary header
        ctx.response()
            .putHeader(HttpHeaders.VARY, setOf(HttpHeaders.ACCEPT, HttpHeaders.AUTHORIZATION, "Origin").joinToString())
        when (ctx.request().method()) {
            HttpMethod.HEAD, HttpMethod.OPTIONS -> handleHead(ctx, solidRequestContext)
            HttpMethod.GET -> handleGet(ctx, solidRequestContext)
            HttpMethod.POST -> handlePost(ctx, solidRequestContext)
            HttpMethod.PUT -> handlePut(ctx, solidRequestContext)
            HttpMethod.DELETE -> handleDelete(ctx, solidRequestContext)
            HttpMethod.PATCH -> handlePatch(ctx, solidRequestContext)
            else -> ctx.response().setStatusCode(405).end().toUni()
        }
            // Handle unexpected errors and subscribe
            .onFailure().recoverWithUni { err ->
                if (err is ClientErrorException) {
                    ctx.response().setStatusCode(err.response.status).end(err.message ?: "").toUni()
                } else {
                    Log.error("Internal server error while processing Solid request", err)
                    if (!ctx.response().closed()) {
                        ctx.response().setStatusCode(500).end(err.message).toUni()
                    } else {
                        Uni.createFrom().voidItem()
                    }
                }
            }
            .eventually(Supplier {
                // Emit storage event after successful operation
                emitStorageEvent(ctx, solidRequestContext)
            })
            .subscribe().with {}
    }

    fun handleHead(ctx: RoutingContext, solidRequestContext: SolidResourceRequestContext): Uni<Void> {
        return solidApi.getContextualResource(solidRequestContext, SolidResource::class).getContent()
            .chain { metadata ->
                // Add standard Solid headers
                addSolidHeaders(ctx, solidRequestContext, metadata != null)
                if (metadata == null) {
                    ctx.response().setStatusCode(404).end().toUni()
                } else {
                    ctx.response().end().toUni()
                }
            }
    }

    fun handleGet(ctx: RoutingContext, solidRequestContext: SolidResourceRequestContext): Uni<Void> {
        return solidApi.getContextualResource(solidRequestContext, SolidResource::class).getContent()
            .chain { response ->
                // Add standard Solid headers
                addSolidHeaders(ctx, solidRequestContext, response != null)
                if (response == null) {
                    ctx.response().setStatusCode(404).end().toUni()
                } else {
                    mapResponse(response, ctx)
                }
            }
    }

    fun handlePost(ctx: RoutingContext, solidRequestContext: SolidResourceRequestContext): Uni<Void> {
        val contentType = ctx.request().getHeader(HttpHeaders.CONTENT_TYPE)
        return (if (contentType == null && !solidRequestContext.isContainerPath()) {
            ctx.response().setStatusCode(400)
                .end("Content-Type header is required for POST requests that create a document.").toUni()
        } else if (solidRequestContext.isContainerPath()) {
            val slug = ctx.request().getHeader("Slug") ?: Hashing.farmHashFingerprint64()
                .hashString(UUID.randomUUID().toString(), Charsets.UTF_8).toString()
            ctx.put(NEW_DOC_KEY, slug)
            val containerType: String? =
                parseLinkHeaders(ctx.request().headers()).getLinkByRelationship("type")?.uri?.toASCIIString()
            when {
                containerType == null || isSupportedContainerType(containerType) -> {
                    solidApi.getContextualResource(solidRequestContext, SolidContainer::class).createChildResource(
                        ctx.bodyAsInputStream(),
                        slug,
                        containerType
                    )
                        .chain { newUri ->
                            ctx.response().setStatusCode(201)
                                .putHeader(HttpHeaders.LOCATION, newUri.toASCIIString())
                                .end().toUni()
                        }
                }

                else -> ctx.response().setStatusCode(400)
                    .end("The given container type ('$containerType') is not supported.")
                    .toUni()
            }
        } else {
            ctx.response().setStatusCode(405).end("The given path is not a container.").toUni()
        })
    }

    fun handlePut(ctx: RoutingContext, solidRequestContext: SolidResourceRequestContext): Uni<Void> {
        val containerType: String? =
            parseLinkHeaders(ctx.request().headers()).getLinkByRelationship("type")?.uri?.toASCIIString()
        val contentType = ctx.request().getHeader(HttpHeaders.CONTENT_TYPE)
        val solidContainer = solidApi.getContextualResource(solidRequestContext, SolidContainer::class)
        val solidDocument = solidApi.getContextualResource(solidRequestContext, SolidDocument::class)
        return (if (contentType == null && !solidRequestContext.isContainerPath()) {
            ctx.response().setStatusCode(400).end("Content-Type header is required for document PUT requests.").toUni()
        } else {
            // TODO: These types of checks are not concurrency-safe...
            Uni.combine().all().unis(
                solidDocument.isPresent(),
                solidContainer.isPresent()
            ).asTuple().map { it.asList().map { it as Boolean } }
                .chain { (documentExists, containerExists) ->
                    if (solidRequestContext.isContainerPath()) {
                        when {
                            documentExists -> ctx.response().setStatusCode(409)
                                .end("A document with the given name already exists.").toUni()

                            containerExists -> ctx.response().setStatusCode(409)
                                .end("Existing containers cannot be updated via PUT.").toUni()

                            !isSupportedContainerType(containerType) -> ctx.response().setStatusCode(400)
                                .end("Container type is missing or not supported.").toUni()

                            else -> solidContainer.create(containerType!!).chain { newUri ->
                                ctx.response().setStatusCode(201)
                                    .putHeader(HttpHeaders.LOCATION, newUri.toASCIIString())
                                    .end().toUni()
                            }
                        }
                    } else if (containerType != null) {
                        ctx.response().setStatusCode(400)
                            .end("Containers should have a `/` at the end of their path, resources should not.").toUni()
                    } else if (!containerExists) {
                        (solidApi.getContextualResource(
                            solidRequestContext,
                            SolidResource::class
                        ) as SolidDocument).setContent(ctx.bodyAsInputStream())
                            .chain { newUri ->
                                if (newUri != null) {
                                    ctx.response().setStatusCode(201)
                                        .putHeader(HttpHeaders.LOCATION, newUri.toASCIIString())
                                        .end().toUni()
                                } else {
                                    ctx.response().setStatusCode(204).end().toUni()
                                }
                            }
                    } else {
                        ctx.response().setStatusCode(409)
                            .end("A container with the given name already exists.").toUni()
                    }
                }
        })
    }

    fun handleDelete(ctx: RoutingContext, solidRequestContext: SolidResourceRequestContext): Uni<Void> {
        val resource = solidApi.getContextualResource(solidRequestContext, SolidResource::class)
        return if (solidRequestContext.isRoot()) {
            ctx.response().setStatusCode(405).end("The root container cannot be deleted.").toUni()
        } else {
            resource.isPresent().chain { exists ->
                if (!exists) {
                    ctx.response().setStatusCode(404).end().toUni()
                } else {
                    resource.delete().chain { _ ->
                        ctx.response().setStatusCode(204).end().toUni()
                    }
                }
            }
        }
    }

    fun handlePatch(ctx: RoutingContext, solidRequestContext: SolidResourceRequestContext): Uni<Void> {
        val contentType = ctx.request().getHeader(HttpHeaders.CONTENT_TYPE)?.let { MediaType.valueOf(it) }
        return if (solidRequestContext.isContainerPath()) {
            ctx.response().setStatusCode(405)
                .end("PATCH requests are not allowed on container resources.").toUni()
        } else if (contentType == null) {
            Log.warn("No Content-Type header provided for PATCH request")
            ctx.response().setStatusCode(400)
                .end("Content-Type header is required for PATCH requests.").toUni()
        } else {
            patchParsers.find { it.isSupportedMediaType(contentType) }
                ?.let { applicableParser ->
                    val parsedPatch =
                        applicableParser.parse(
                            solidRequestContext.getResourceUri().toASCIIString(),
                            ctx.body().asString()
                        )
                    (solidApi.getContextualResource(solidRequestContext, SolidResource::class) as SolidDocument).modify(
                        parsedPatch
                    )
                        .chain { newUri ->
                            if (newUri != null) {
                                ctx.response().setStatusCode(201)
                                    .putHeader(HttpHeaders.LOCATION, newUri.toASCIIString())
                                    .end().toUni()
                            } else {
                                ctx.response().setStatusCode(204).end().toUni()
                            }
                        }
                } ?: run {
                Log.debug("No applicable PATCH parser found for media type: $contentType")
                ctx.response().setStatusCode(415)
                    .end("The given PATCH media type ('$contentType') is not supported.").toUni()
            }
        }
    }

    private fun mapResponse(response: ContentResponse, ctx: RoutingContext): Uni<Void> {
        ctx.response().isChunked = true
        // Check media type compatibility
        val jaxrsHeaders = HttpHeadersImpl(ctx.request().headers())
        val responseMediaType = MediaType.valueOf(response.contentType)
        val acceptableMediaTypes =
            jaxrsHeaders.acceptableMediaTypes.takeIf { it.isNotEmpty() } ?: listOf(MediaType.WILDCARD_TYPE)

        // Find the first acceptable media type that is compatible with or can be converted from the response media type
        val targetMediaType = acceptableMediaTypes.firstOrNull {
            it.isCompatible(responseMediaType) || responseMediaType.isRDFConvertableTo(it)
        }

        return when {
            // If the response media type as-is is acceptable, return it directly (no conversion overhead)
            responseMediaType.isCompatible(targetMediaType) -> {
                ctx.response().putHeader(HttpHeaders.CONTENT_TYPE, responseMediaType.toString())
                response.contentStream
                    .onItem().transformToUniAndConcatenate { buffer -> ctx.response().write(buffer).toUni() }
                    .skipToLast()
                    .chain { _ -> ctx.response().end().toUni() }
            }
            // If the response media type can be converted to any of the acceptable media types, do the conversion
            responseMediaType.isRDFConvertableTo(targetMediaType) -> {
                val targetMediaType = acceptableMediaTypes.first { responseMediaType.isRDFConvertableTo(it) }
                ctx.response().putHeader(HttpHeaders.CONTENT_TYPE, targetMediaType.toString())
                toInputStream(response.contentStream).chain { inputStream ->
                    // TODO: is there a more efficient way to do this without buffering the entire response in memory?
                    convertRDFStream(
                        ctx.request().absoluteURI(),
                        inputStream,
                        responseMediaType.toString(),
                        targetMediaType.toString()
                    ).chain { buffer ->
                        ctx.response().end(buffer).toUni()
                    }
                }
            }

            else -> {
                // The response media type is not acceptable and cannot be converted to any of the accepted media types
                ctx.response().setStatusCode(406).end().toUni()
            }
        }
    }

    private fun addSolidHeaders(
        ctx: RoutingContext,
        solidRequestContext: SolidResourceRequestContext,
        resourceExists: Boolean
    ) {
        ctx.response().putHeader(
            HttpHeaders.ALLOW, if (solidRequestContext.isContainerPath()) {
                if (resourceExists) {
                    setOf(HttpMethod.OPTIONS, HttpMethod.HEAD, HttpMethod.GET, HttpMethod.POST)
                } else {
                    setOf(HttpMethod.PUT)
                }.joinToString()
            } else {
                if (resourceExists) {
                    setOf(
                        HttpMethod.OPTIONS,
                        HttpMethod.HEAD,
                        HttpMethod.GET,
                        HttpMethod.PATCH,
                        HttpMethod.PUT,
                        HttpMethod.DELETE
                    )
                } else {
                    setOf(HttpMethod.PATCH, HttpMethod.PUT)
                }.joinToString()
            }
        )

        if (resourceExists) {
            val links = listOfNotNull(
                // If root container, add storage link
                if (solidRequestContext.isRoot()) listOf("<${PIMVocab.Storage}>; rel=\"type\"") else null,
                (if (solidRequestContext.isContainerPath()) listOf(
                    LDPVocab.Container,
                    LDPVocab.BasicContainer,
                    LDPVocab.Resource
                ) else listOf(LDPVocab.Resource)).map { "<$it>; rel=\"type\"" },
                listOf("<${solidRequestContext.getResourceUri()}.meta>; rel=\"describedBy\"")
            ).flatMap { it }.joinToString()
            ctx.response().putHeader(HttpHeaders.LINK, links)
        }
    }

    private fun toInputStream(out: Multi<Buffer>): Uni<InputStream> {
        return out
            .onItem().transform { it.bytes }
            .collect().asList()
            .map { list ->
                val byteArray = list.reduce { acc, bytes -> acc + bytes }
                byteArray.inputStream()
            }
    }

    private fun convertRDFStream(
        resourceUri: String,
        inputStream: InputStream,
        inMediaType: String,
        outMediaType: String
    ): Uni<Buffer> = vertx.executeBlocking {
        ByteArrayOutputStream().use { outputStream ->
            val rdfFormatIn = Rio.getParserFormatForMIMEType(inMediaType).orElseThrow {
                IllegalArgumentException("Unsupported RDF media type: $inMediaType")
            }
            val rdfFormatOut = Rio.getParserFormatForMIMEType(outMediaType).orElseThrow {
                IllegalArgumentException("Unsupported RDF media type: $outMediaType")
            }
            val model = Rio.parse(inputStream, resourceUri, rdfFormatIn)
            Rio.write(model, outputStream, rdfFormatOut)
            Buffer.buffer(outputStream.toByteArray())
        }
    }

    private fun parseLinkHeaders(headers: MultiMap): LinkHeaders {
        val tmp = MultivaluedHashMap<String, Any>()
        headers.names().forEach { name -> tmp.addAll(StringUtils.capitalize(name.lowercase()), headers[name]) }
        return LinkHeaders(tmp)
    }

    private fun emitStorageEvent(ctx: RoutingContext, solidRequestContext: SolidResourceRequestContext): Uni<Void> {
        return if (ctx.request().method() in setOf(
                HttpMethod.GET,
                HttpMethod.HEAD,
                HttpMethod.PUT,
                HttpMethod.POST,
                HttpMethod.PATCH,
                HttpMethod.DELETE
            )
        ) {
            val key =
                solidRequestContext.getResourceUri().toASCIIString()
                    .removePrefix(solidRequestContext.getRoot().toASCIIString()) + (ctx.get<String>(
                    NEW_DOC_KEY
                ) ?: "")
            val bucketId = S3Utils.getBucket("${solidRequestContext.getBaseUri()}/${solidRequestContext.getPodName()}")
            val event = StorageEvent(
                id = "urn:kvasir:storage-events:solid:${UUID.randomUUID()}",
                requestingUser = ctx.user()?.subject() ?: AuthConstants.ANONYMOUS_USERNAME,
                timestamp = Instant.now(),
                podId = solidRequestContext.getPodId(),
                objectId = key,
                externalObjectUri = solidRequestContext.getResourceUri().toASCIIString(),
                internalStorageUri = "${s3Config.endpoint().removeSuffix("/")}/${bucketId}/$key",
                // TODO: add support for specifying a version here!
                versionId = null,
                eventType = when (ctx.request().method()) {
                    HttpMethod.HEAD, HttpMethod.GET -> {
                        if (solidRequestContext.isMetadataRequest()) {
                            StorageEventType.GET_OBJECT_METADATA
                        } else {
                            StorageEventType.GET_OBJECT
                        }
                    }

                    HttpMethod.POST -> StorageEventType.CREATE_OBJECT
                    HttpMethod.PUT, HttpMethod.PATCH -> {
                        if (solidRequestContext.isMetadataRequest()) {
                            StorageEventType.WRITE_OBJECT_METADATA
                        } else {
                            StorageEventType.PUT_OBJECT
                        }
                    }

                    HttpMethod.DELETE -> StorageEventType.DELETE_OBJECT
                    else -> throw IllegalArgumentException("Unsupported HTTP method for storage event emission")
                }
            )
            storageMutationsEmitter.getEmitter().send(event)
        } else {
            Uni.createFrom().voidItem()
        }
    }

}