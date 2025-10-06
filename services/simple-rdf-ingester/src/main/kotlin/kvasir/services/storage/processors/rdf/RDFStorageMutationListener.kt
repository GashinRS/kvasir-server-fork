package kvasir.services.storage.processors.rdf

import io.quarkus.logging.Log
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.kg.ChangeRequest
import kvasir.definitions.kg.PodStoreFactory
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.definitions.storage.StorageEvent
import kvasir.definitions.storage.StorageEventType
import kvasir.plugins.messaging.kafka.Channels
import kvasir.utils.idgen.ChangeRequestId
import kvasir.utils.s3.S3Utils
import kvasir.utils.s3.getObjectContentType
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Outgoing
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest

/**
 * Processor that listens for storage mutations on files that contain RDF data
 * and transforms these into Kvasir change events (modifying the pod KG)
 */
@ApplicationScoped
class RDFStorageMutationListener(
    private val s3Client: S3AsyncClient,
    private val podStoreFactory: PodStoreFactory
) {

    @Incoming(Channels.STORAGE_EVENTS_SUBSCRIBE)
    @Outgoing(Channels.CHANGE_REQUESTS_PUBLISH)
    fun consumeAndLog(storageEvents: Multi<StorageEvent>): Multi<ChangeRequest> {
        return storageEvents
            .onItem().transformToUniAndConcatenate { event ->
                podStoreFactory.createPodStore().findById(event.podId).map { event to (it?.getAutoIngestRDF() == true) }
            }
            .filter { (event, autoIngestEnabled) -> autoIngestEnabled && event.type.mutation }
            .map { (event, _) -> event }
            .onItem()
            .transformToUniAndConcatenate { event ->
                val bucketId = event.sliceId?.let { S3Utils.getBucket(it) } ?: S3Utils.getBucket(event.podId)

                // If the operation is of type DELETE_OBJECT, we need to look up the version previous to the deletion
                if (event.type == StorageEventType.DELETE_OBJECT) {
                    Uni.createFrom().completionStage {
                        s3Client.listObjectVersions(
                            ListObjectVersionsRequest.builder().bucket(bucketId).prefix(event.objectId).build()
                        )
                    }
                        .map { resp ->
                            // Find the version just before the deleted one (the one with the highest lastModified date)
                            resp.versions().takeIf { it.isNotEmpty() }?.maxBy { it.lastModified() }
                                ?.let { previousVersion ->
                                    event.copy(versionId = previousVersion.versionId())
                                }
                        }
                } else {
                    Uni.createFrom().item(event)
                }
            }
            .onItem()
            .transformToUniAndConcatenate { event ->
                val bucketId = event.sliceId?.let { S3Utils.getBucket(it) } ?: S3Utils.getBucket(event.podId)
                s3Client.getObjectContentType(bucketId, event.objectId, event.versionId).map { event to it }
            }
            .filter { (_, rawContentType) ->
                // Only process objects that are RDF data
                val contentType =
                    MediaType.valueOf(rawContentType).let { "${it.type}/${it.subtype}" }
                RDFMediaTypes.supportedTypes.contains(contentType)
            }
            .map { (event, _) ->
                // Transform the object into a Kvasir change request
                val id = ChangeRequestId.generate(event.externalObjectUri.substringBefore("/s3") + "/changes").encode()
                println(event)
                when (event.type) {
                    StorageEventType.PUT_OBJECT, StorageEventType.COMPLETE_MULTIPART_UPLOAD, StorageEventType.RESTORE_OBJECT -> ChangeRequest(
                        id = id,
                        requestingUser = event.requestingUser ?: "",
                        podId = event.podId,
                        insertFromRefs = listOf(
                            listOfNotNull(
                                JsonLdKeywords.type to KvasirVocab.S3Reference,
                                KvasirVocab.key to event.objectId,
                                event.versionId?.let { KvasirVocab.versionId to it }
                            ).toMap()
                        ),
                        deleteFromRefs = emptyList()
                    )

                    StorageEventType.DELETE_OBJECT -> ChangeRequest(
                        id = id,
                        requestingUser = event.requestingUser ?: "",
                        podId = event.podId,
                        insertFromRefs = emptyList(),
                        deleteFromRefs = listOf(
                            listOfNotNull(
                                JsonLdKeywords.type to KvasirVocab.S3Reference,
                                KvasirVocab.key to event.objectId,
                                event.versionId?.let { KvasirVocab.versionId to it }
                            ).toMap()
                        )
                    )

                    else -> throw IllegalStateException("Unsupported storage event type: ${event.type}")
                }
            }
            .onFailure().invoke { err ->
                Log.warn("Error processing storage event", err)
            }
    }

}