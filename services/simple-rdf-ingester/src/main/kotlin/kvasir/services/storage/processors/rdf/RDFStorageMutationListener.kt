package kvasir.services.storage.processors.rdf

import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor
import com.github.jsonldjava.utils.JsonUtils
import io.minio.GetObjectArgs
import io.minio.GetObjectResponse
import io.minio.MinioAsyncClient
import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.HttpHeaders
import kvasir.definitions.kg.ChangeRequest
import kvasir.definitions.storage.StorageMutationEvent
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFDataMgr
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Outgoing
import java.io.StringWriter

/**
 * Processor that listens for storage mutations on files that contain RDF data
 * and transforms these into Kvasir change events (modifying the pod KG)
 */
@ApplicationScoped
class RDFStorageMutationListener(
    private val minioClient: MinioAsyncClient
) {

    @Incoming("storage_mutations_subscribe")
    @Outgoing("change_requests_publish")
    fun consumeAndLog(event: StorageMutationEvent): Uni<ChangeRequest> {
        println("Received storage mutation event: $event")
        return Uni.createFrom().completionStage(
            minioClient.getObject(
                GetObjectArgs.builder().bucket(event.podId).`object`(event.objectId).versionId(event.versionId).build()
            )
        )
            .map { resp ->
                val inputModel = ModelFactory.createDefaultModel()
                resp.use { inputStream ->
                    RDFDataMgr.read(inputModel, inputStream, parseLang(resp))
                }
                graphToChangeRequest(event.podId, event.externalObjectUri, inputModel)
            }
    }

    private fun parseLang(resp: GetObjectResponse): Lang? {
        val contentType = resp.headers()[HttpHeaders.CONTENT_TYPE]
        return when (contentType) {
            "text/turtle" -> Lang.TURTLE
            "application/ld+json" -> Lang.JSONLD
            else -> null
        }
    }

    private fun graphToChangeRequest(podId: String, graphId: String, rdfModel: Model): ChangeRequest {
        // TODO: more efficient I/O
        val jsonLdStr = StringWriter().use { writer ->
            RDFDataMgr.write(writer, rdfModel, Lang.JSONLD)
            writer.toString()
        }
        // Read JSON-LD into its expanded form
        val expandedJsonGraph = JsonLdProcessor.expand(JsonUtils.fromString(jsonLdStr))
        Log.debug("Expanded JSON-LD graph: $expandedJsonGraph")
        // Compact the expanded JSON-LD graph with an empty context to get fully qualified IRIs
        val compactFQJsonGraph = JsonLdProcessor.compact(expandedJsonGraph, mapOf<String, Any>(), JsonLdOptions())
        Log.debug("Compacted JSON-LD graph: $compactFQJsonGraph")
        return ChangeRequest(
            podId = podId,
            graph = graphId,
            insert = compactFQJsonGraph["@graph"]?.let { graph -> graph as List<Map<String, Any>> } ?: listOf(
                compactFQJsonGraph
            ),
            // TODO: include instruction to delete previous content
        )
    }

}