package kvasir.services.api.solid

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.vertx.core.buffer.Buffer
import io.vertx.ext.web.RoutingContext
import jakarta.ws.rs.core.MediaType
import org.eclipse.rdf4j.model.Statement
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import kotlin.reflect.KClass

interface SolidApi {
    fun <T : SolidResource> getContextualResource(ctx: SolidResourceRequestContext, clazz: KClass<T>): T
}

/**
 * This interface represents a Solid resource.
 */
interface SolidResource {

    /**
     * Check whether the resource is present.
     */
    fun isPresent(): Uni<Boolean>

    /**
     * Retrieve the content of the resource.
     */
    fun getContent(): Uni<ContentResponse?>

    /**
     * Delete the resource.
     */
    fun delete(): Uni<Void>

}

/**
 * This interface represents a Solid container resource.
 */
interface SolidContainer : SolidResource {

    /**
     * Create the container.
     */
    fun create(containerType: String): Uni<URI>

    /**
     * Create a child resource within this container.
     *
     * @param content A stream of the content of the resource.
     * @param slug A name suggestion for the resource (is appended to the URI of the container).
     * @param containerType If containerType is non-null, a child container is created instead of a document.
     */
    fun createChildResource(
        content: InputStream,
        slug: String,
        containerType: String? = null
    ): Uni<URI>

}

/**
 * This interface represents a Solid document resource.
 */
interface SolidDocument : SolidResource {


    /**
     * Set the content for the Document.
     *
     * @param content The content to be set (as a stream).
     * @return Optional URI for the Document (returned when the document did not exist before), as a Uni.
     */
    fun setContent(content: InputStream): Uni<URI?>


    /**
     * Patch the Document.
     *
     * @param patch A supported Patch string to be applied.
     * @return Optional URI for the Document (returned when the document did not exist before).
     */
    fun modify(patch: Patch): Uni<URI?>

}

interface SolidDocumentMetadata : SolidDocument

data class ContentResponse(
    val contentStream: Multi<Buffer>,
    val contentType: String
)

data class Patch(
    val docIri: String,
    val deletions: Collection<Statement>,
    val insertions: Collection<Statement>
)

interface PatchParser {

    fun isSupportedMediaType(mediaType: MediaType): Boolean

    fun parse(docIri: String, input: String): Patch =
        ByteArrayInputStream(input.toByteArray()).use { bis ->
            parse(docIri, bis)
        }

    fun parse(docIri: String, inputStream: InputStream): Patch

}

interface SolidResourceRequestContextProvider {
    fun getContext(ctx: RoutingContext): SolidResourceRequestContext
}

interface SolidResourceRequestContext {

    fun isRoot(): Boolean

    fun isContainerPath(): Boolean

    fun isMetadataRequest(): Boolean

    fun getResourceUri(): URI

    fun getBaseUri(): String

    fun getPodId(): String

    fun getPodName(): String

    fun getRoot(): URI

    fun getContentType(): String?

    fun getContentLength(): Long

}