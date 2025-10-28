package kvasir.services.api.solid.impl

import com.github.jsonldjava.utils.JsonUtils
import com.github.luben.zstd.Zstd
import io.quarkus.logging.Log
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.infrastructure.Infrastructure
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.Json
import io.vertx.ext.web.RoutingContext
import io.vertx.mutiny.core.Vertx
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.ClientErrorException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.core.HttpHeaders
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.definitions.rdf.XSDVocab
import kvasir.definitions.reactive.toMulti
import kvasir.definitions.reactive.toUni
import kvasir.services.api.solid.*
import kvasir.services.api.solid.vocab.DCVocab
import kvasir.services.api.solid.vocab.LDPVocab
import kvasir.services.api.solid.vocab.PosixStatVocab
import kvasir.utils.http.getChildUri
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.impl.DynamicModelFactory
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.Rio
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.time.Instant
import java.util.*
import kotlin.reflect.KClass

private const val S3_SOLID_METADATA_KEY = "solid-metadata"
private const val S3_SOLID_METADATA_SIZE_KEY = "solid-metadata-size"

@ApplicationScoped
class S3SolidApi(
    private val s3Client: S3AsyncClient,
    private val vertx: Vertx
) : SolidApi {
    override fun <T : SolidResource> getContextualResource(ctx: RoutingContext, clazz: KClass<T>): T {
        return when (clazz) {
            SolidContainer::class -> S3SolidContainer(ctx, s3Client, vertx)
            SolidDocument::class -> S3SolidDocument(ctx, s3Client, vertx)
            else -> if (ctx.request().path().isContainerPath()) {
                S3SolidContainer(ctx, s3Client, vertx)
            } else if (ctx.isMetadataRequest()) {
                S3SolidDocumentMetadata(ctx, s3Client, vertx)
            } else {
                S3SolidDocument(ctx, s3Client, vertx)
            }
        } as T
    }
}

abstract class S3SolidResource(
    protected val context: RoutingContext,
    protected val s3Client: S3AsyncClient,
    protected val vertx: Vertx
) :
    SolidResource {

    abstract fun getResourceKey(): String

    protected fun ensureContainerExists(
        key: String,
        containerType: String = LDPVocab.BasicContainer
    ): Uni<Void> {
        return when {
            // No need to create the root container
            key.isEmpty() || key == "/" -> Uni.createFrom().voidItem()
            containerType != LDPVocab.BasicContainer -> Uni.createFrom()
                .failure(BadRequestException("Only ldp:BasicContainer is supported as container type"))

            else -> {
                Uni.combine().all().unis(
                    s3Client.objectExists(context.getBucketId(), key),
                    s3Client.objectExists(context.getBucketId(), key.removeSuffix("/"))
                ).asTuple().map { tuple -> tuple.asList().map { it as Boolean } }
                    .chain { (existsAsFolder, existsAsDoc) ->
                        when {
                            existsAsDoc -> Uni.createFrom().failure(
                                ClientErrorException(
                                    "A document with the same name as the to be created container already exists.",
                                    409
                                )
                            )

                            existsAsFolder -> Uni.createFrom().voidItem()
                            else -> {
                                ensureContainerExists(
                                    getParentKey(key),
                                    containerType
                                )
                                    .chain { _ ->
                                        Log.debug("Creating container at key '$key' with type '$containerType'")
                                        s3Client.putObject(
                                            PutObjectRequest.builder().bucket(context.getBucketId()).key(key).build(),
                                            AsyncRequestBody.empty()
                                        ).toUni().replaceWithVoid()
                                    }
                            }
                        }
                    }
            }
        }
    }

    protected fun getParentKey(key: String): String {
        return key.split("/").filterNot { it.isBlank() }.dropLast(1).joinToString("/", postfix = "/")
    }

}


class S3SolidContainer(context: RoutingContext, s3Client: S3AsyncClient, vertx: Vertx) :
    S3SolidResource(context, s3Client, vertx),
    SolidContainer {
    override fun isPresent(): Uni<Boolean> {
        return if (context.isRoot()) {
            // The root container always exists
            Uni.createFrom().item(true)
        } else {
            s3Client.objectsWithPrefixExist(context.getBucketId(), getResourceKey())
        }
    }

    override fun getContent(): Uni<ContentResponse?> {
        return isPresent().chain { exists ->
            if (exists) {
                s3Client.listObjectsV2Paginator(
                    ListObjectsV2Request.builder().bucket(context.getBucketId()).prefix(getResourceKey())
                        .delimiter("/").build()
                ).toMulti()
                    // TODO: Don't collect all results at once, but stream them as they come in (this would require a streamable RDF representation)
                    .collect().asList().map { results ->
                        val containerMd = listOfNotNull(
                            JsonLdKeywords.id to context.getResourceUri().toASCIIString(),
                            JsonLdKeywords.type to listOf(
                                LDPVocab.Resource,
                                LDPVocab.Container,
                                LDPVocab.BasicContainer
                            ),
                            LDPVocab.contains to results
                                .flatMap { resp ->
                                    resp.contents().map {
                                        Containment(
                                            id = "${context.getRoot()}${it.key()}",
                                            types = listOf(LDPVocab.Resource) + if (it.key().endsWith("/")) listOf(
                                                LDPVocab.Container,
                                                LDPVocab.BasicContainer
                                            ) else emptyList(),
                                            lastModified = it.lastModified(),
                                            size = it.size()
                                        )
                                    } + resp.commonPrefixes().map {
                                        Containment(
                                            "${context.getRoot()}${it.prefix()}", listOf(
                                                LDPVocab.Resource, LDPVocab.Container, LDPVocab.BasicContainer
                                            )
                                        )
                                    }
                                }
                                // Filter out the container itself
                                .filter { it.id != context.getResourceUri().toASCIIString() }
                                .map { entry ->
                                    listOfNotNull(
                                        JsonLdKeywords.id to entry.id,
                                        JsonLdKeywords.type to entry.types,
                                        entry.lastModified?.let { PosixStatVocab.mtime to it.epochSecond },
                                        entry.lastModified?.let {
                                            DCVocab.modified to mapOf(
                                                JsonLdKeywords.value to it.toString(),
                                                JsonLdKeywords.type to XSDVocab.dateTime
                                            )
                                        },
                                        entry.size?.let { PosixStatVocab.size to it }
                                    ).toMap()
                                }
                        ).toMap()
                        ContentResponse(
                            Multi.createFrom().item(Json.encodeToBuffer(containerMd)),
                            RDFMediaTypes.JSON_LD
                        )
                    }
            } else {
                Uni.createFrom().failure(NotFoundException())
            }
        }
    }

    override fun delete(): Uni<Void> {
        return Uni.combine().all().unis(
            s3Client.objectExists(context.getBucketId(), getResourceKey()),
            s3Client.prefixHasChildren(context.getBucketId(), getResourceKey())
        ).asTuple().map { result -> result.asList().map { it as Boolean } }
            .chain { (containerObjExists, containerHasChildren) ->
                when {
                    // Cannot delete a non-empty container
                    containerHasChildren -> Uni.createFrom()
                        .failure(ClientErrorException("Cannot delete a non-empty container.", 409))
                    // If a container object was created, delete it
                    containerObjExists -> s3Client.deleteObject(
                        DeleteObjectRequest.builder().bucket(context.getBucketId()).key(getResourceKey()).build()
                    ).toUni().replaceWithVoid()
                    // If no container object was created, just return (compatibility with S3-backed containers that have no object at the container key)
                    else -> Uni.createFrom().failure(NotFoundException())
                }
            }
    }

    override fun create(containerType: String): Uni<URI> {
        return ensureContainerExists(getResourceKey(), containerType).map { context.getResourceUri() }
    }

    override fun createChildResource(
        content: InputStream,
        slug: String,
        containerType: String?
    ): Uni<URI> {
        val childKey = "${getResourceKey()}$slug"
        // Check if the container exists and check if the to be created resource exists (in parallel)
        return Uni.combine().all().unis(
            isPresent(),
            s3Client.objectExists(context.getBucketId(), childKey),
            s3Client.objectsWithPrefixExist(context.getBucketId(), "$childKey/"),
        ).asTuple().chain { existChecks ->
            val (containerExists, childDocumentExists, childContainerExists) = existChecks.map { it as Boolean }
            if (!containerExists) {
                return@chain Uni.createFrom().failure(NotFoundException())
            }

            if (containerType == null) {
                if (childContainerExists) {
                    return@chain Uni.createFrom()
                        .failure(ClientErrorException("A container with the given name already exists", 409))
                }

                // Create child document
                val contentLength = context.request().getHeader(HttpHeaders.CONTENT_LENGTH).toLong()
                s3Client.putObject(
                    PutObjectRequest.builder().bucket(context.getBucketId()).key(childKey).contentType(
                        context.request().getHeader(
                            HttpHeaders.CONTENT_TYPE
                        )
                    ).build(),
                    AsyncRequestBody.fromInputStream(content, contentLength, Infrastructure.getDefaultWorkerPool())
                )
                    .toUni().map {
                        context.getResourceUri().getChildUri(slug)
                    }
            } else {
                if (childDocumentExists) {
                    return@chain Uni.createFrom()
                        .failure(ClientErrorException("A document with the given name already exists", 409))
                }
                ensureContainerExists("$childKey/", containerType).map {
                    context.getResourceUri().getChildUri("$slug/")
                }
            }
        }
    }

    override fun getResourceKey(): String {
        return if (context.isRoot()) "" else context.getResourceUri().path.substringAfter("/solid/")
            .removeSuffix(METADATA_RESOURCE_SUFFIX)
            .removeSuffix("/").plus("/")
    }

}


class S3SolidDocument(context: RoutingContext, s3Client: S3AsyncClient, vertx: Vertx) :
    S3SolidResource(context, s3Client, vertx),
    SolidDocument {
    override fun isPresent(): Uni<Boolean> {
        return s3Client.objectExists(context.getBucketId(), getResourceKey())
    }

    override fun getContent(): Uni<ContentResponse?> {
        return s3Client.getObject(
            GetObjectRequest.builder().bucket(context.getBucketId()).key(getResourceKey()).build(),
            AsyncResponseTransformer.toPublisher()
        ).toUni()
            .onItem().transform { resp ->
                ContentResponse(
                    resp.toMulti().map { Buffer.buffer(it.array()) },
                    resp.response().contentType()
                )
            }
            .onFailure(NoSuchKeyException::class.java).recoverWithNull()
    }

    override fun delete(): Uni<Void> {
        return s3Client.deleteObject(
            DeleteObjectRequest.builder().bucket(context.getBucketId()).key(getResourceKey()).build()
        )
            .toUni().replaceWithVoid()
    }

    override fun setContent(content: InputStream): Uni<URI?> {
        val contentLength = context.request().getHeader(HttpHeaders.CONTENT_LENGTH).toLong()
        // Check if the object exists
        return s3Client.objectExists(context.getBucketId(), getResourceKey()).chain { exists ->
            // Upload the provided content
            s3Client.putObject(
                PutObjectRequest.builder().bucket(context.getBucketId()).key(getResourceKey()).contentType(
                    context.request().getHeader(
                        HttpHeaders.CONTENT_TYPE
                    )
                ).build(),
                AsyncRequestBody.fromInputStream(content, contentLength, Infrastructure.getDefaultWorkerPool())
            ).toUni()
                .chain { resp ->
                    // If the object did not exist before, ensure its parent containers exist, then return the URI of the created object
                    if (!exists) {
                        ensureContainerExists(getParentKey(getResourceKey())).map {
                            context.getResourceUri()
                        }
                    } else {
                        // If the object existed before, just return null
                        Uni.createFrom().nullItem()
                    }
                }
        }
    }

    override fun modify(patch: Patch): Uni<URI?> {
        // Implement PATCH support (by loading the resource in RDF4J, applying the patch, and then writing it back)
        return s3Client.getObject(
            GetObjectRequest.builder().bucket(context.getBucketId()).key(getResourceKey()).build(),
            AsyncResponseTransformer.toBlockingInputStream()
        ).toUni()
            .chain { resp ->
                val mediaType = resp.response().contentType()
                vertx.executeBlocking {
                    val rdfType = Rio.getParserFormatForMIMEType(mediaType).orElseThrow {
                        IllegalArgumentException("Unsupported RDF media type: $mediaType")
                    }
                    ReadModelResult(rdfType, Rio.parse(resp, context.getResourceUri().toASCIIString(), rdfType), true)
                }
            }
            .onFailure(NoSuchKeyException::class.java).recoverWithUni { _ ->
                // Document does not exist yet, ensure parent containers exist and create an empty model
                ensureContainerExists(getParentKey(getResourceKey())).map {
                    ReadModelResult(RDFFormat.TURTLE, DynamicModelFactory().createEmptyModel(), false)
                }
            }
            .chain { (rdfType, model, existed) ->
                vertx.executeBlocking {
                    // Apply the patch
                    model.removeAll(patch.deletions.toSet())
                    model.addAll(patch.insertions)
                    ByteArrayOutputStream().use { bos ->
                        Rio.write(model, bos, rdfType)
                        bos.toByteArray()
                    }
                }
                    .chain { updatedFileAsBytes ->
                        s3Client.putObject(
                            PutObjectRequest.builder().bucket(context.getBucketId()).key(getResourceKey())
                                .contentType(rdfType.defaultMIMEType).build(),
                            AsyncRequestBody.fromBytes(updatedFileAsBytes)
                        ).toUni().map {
                            if (!existed) {
                                context.getResourceUri()
                            } else {
                                null
                            }
                        }
                    }
            }
    }

    override fun getResourceKey(): String {
        return context.getResourceUri().path.substringAfter("/solid/").removeSuffix(METADATA_RESOURCE_SUFFIX)
            .removeSuffix("/")
    }

}

class S3SolidDocumentMetadata(
    context: RoutingContext,
    s3Client: S3AsyncClient,
    vertx: Vertx
) : S3SolidResource(context, s3Client, vertx), SolidDocumentMetadata {
    override fun getResourceKey(): String {
        return context.getResourceUri().path.substringAfter("/solid/").removeSuffix(METADATA_RESOURCE_SUFFIX)
            .removeSuffix("/")
    }

    override fun isPresent(): Uni<Boolean> {
        return s3Client.objectExists(context.getBucketId(), getResourceKey())
    }

    override fun getContent(): Uni<ContentResponse?> {
        return s3Client.headObject(
            HeadObjectRequest.builder().bucket(context.getBucketId()).key(getResourceKey()).build()
        ).toUni()
            .map { resp ->
                val metaContent = JsonUtils.fromString(resp.metadata()[S3_SOLID_METADATA_KEY]?.let { encodedContent ->
                    // Decompress the stored metadata content
                    String(
                        Zstd.decompress(
                            Base64.getDecoder().decode(encodedContent.toByteArray()),
                            resp.metadata()[S3_SOLID_METADATA_SIZE_KEY]!!.toInt()
                        )
                    )
                } ?: "{}") as JSONObject + mapOf(
                    // Add basic metadata
                    PosixStatVocab.mtime to resp.lastModified().epochSecond,
                    DCVocab.modified to mapOf(
                        JsonLdKeywords.value to resp.lastModified().toString(),
                        JsonLdKeywords.type to XSDVocab.dateTime
                    ),
                    PosixStatVocab.size to resp.contentLength()
                )
                ContentResponse(
                    Multi.createFrom().item(Buffer.buffer(JsonUtils.toString(metaContent).toByteArray())),
                    RDFMediaTypes.JSON_LD
                )
            }
            .onFailure(NoSuchKeyException::class.java).recoverWithNull()
    }

    override fun delete(): Uni<Void> {
        // Clear the metadata by removing the relevant metadata entries
        return updateObjectMetadata { metadata ->
            Uni.createFrom()
                .item { metadata.filterKeys { it != S3_SOLID_METADATA_KEY  && it != S3_SOLID_METADATA_SIZE_KEY } }
        }
    }

    override fun setContent(content: InputStream): Uni<URI?> {
        val mediaType = context.request().getHeader(HttpHeaders.CONTENT_TYPE)
        return vertx.executeBlocking {
            // Load content into RD4J model
            val rdfType = Rio.getParserFormatForMIMEType(mediaType).orElseThrow {
                IllegalArgumentException("Unsupported RDF media type: $mediaType")
            }
            try {
                val model = Rio.parse(content, context.getResourceUri().toASCIIString(), rdfType)
                // Serialize as JSON-LD (metadata is always stored as compressed JSON-LD)
                ByteArrayOutputStream().use { bos ->
                    Rio.write(model, bos, RDFFormat.JSONLD)
                    bos.toByteArray()
                }
            } catch (t: Throwable) {
                throw BadRequestException("Failed to parse provided metadata content as RDF: ${t.message}", t)
            }
        }
            .chain { content ->
                updateObjectMetadata { metadata ->
                    Uni.createFrom().item {
                        metadata + mapOf(
                            S3_SOLID_METADATA_KEY to Base64.getEncoder().encodeToString(Zstd.compress(content)),
                            S3_SOLID_METADATA_SIZE_KEY to content.size.toString()
                        )
                    }
                }
            }
            .map { null }
    }

    override fun modify(patch: Patch): Uni<URI?> {
        return updateObjectMetadata { metadata ->
            val metaContent = metadata[S3_SOLID_METADATA_KEY]?.let { encodedContent ->
                // Decompress the stored metadata content
                String(
                    Zstd.decompress(
                        Base64.getDecoder().decode(encodedContent.toByteArray()),
                        metadata[S3_SOLID_METADATA_SIZE_KEY]!!.toInt()
                    )
                )
            } ?: "{}"
            vertx.executeBlocking {
                ByteArrayInputStream(metaContent.toByteArray()).use { bis ->
                    val model = Rio.parse(bis, context.getResourceUri().toASCIIString(), RDFFormat.JSONLD)
                    // Apply the patch
                    model.removeAll(patch.deletions.toSet())
                    model.addAll(patch.insertions)
                    val output = ByteArrayOutputStream().use { bos ->
                        Rio.write(model, bos, RDFFormat.JSONLD)
                        bos.toByteArray()
                    }
                    metadata + mapOf(
                        S3_SOLID_METADATA_KEY to Base64.getEncoder().encodeToString(Zstd.compress(output)),
                        S3_SOLID_METADATA_SIZE_KEY to output.size.toString()
                    )
                }
            }
        }.map { null }
    }

    private fun updateObjectMetadata(updateFunction: (Map<String, String>) -> Uni<Map<String, String>>): Uni<Void> {
        return s3Client.headObject(
            HeadObjectRequest.builder().bucket(context.getBucketId()).key(getResourceKey()).build()
        ).toUni()
            .chain { resp ->
                updateFunction(resp.metadata())
                    .chain { updatedMetadata ->
                        s3Client.copyObject(
                            CopyObjectRequest.builder()
                                .sourceBucket(context.getBucketId())
                                .sourceKey(getResourceKey())
                                .destinationBucket(context.getBucketId())
                                .contentType(resp.contentType())
                                .destinationKey(getResourceKey())
                                .metadata(updatedMetadata)
                                .metadataDirective(MetadataDirective.REPLACE)
                                .build()
                        ).toUni().replaceWithVoid()
                    }
            }
    }

}

internal data class ReadModelResult(val rdfType: RDFFormat, val model: Model, val existed: Boolean)

internal data class Containment(
    val id: String,
    val types: List<String>,
    val lastModified: Instant? = null,
    val size: Long? = null
)