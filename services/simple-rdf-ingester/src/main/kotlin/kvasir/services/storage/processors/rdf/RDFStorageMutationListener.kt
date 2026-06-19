package kvasir.services.storage.processors.rdf

import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.KafkaRecord
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.kg.changes.ChangeRequest
import kvasir.definitions.kg.changes.S3Reference
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.definitions.reactive.toUni
import kvasir.definitions.storage.StorageEvent
import kvasir.definitions.storage.StorageEventType
import kvasir.plugins.messaging.kafka.Channels
import kvasir.plugins.storage.s3.S3Utils
import kvasir.plugins.storage.s3.getObjectContentType
import kvasir.utils.idgen.ChangeRequestId
import kvasir.utils.pod.PodConfigProvider
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Message
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest
import java.util.concurrent.CompletionStage
import kotlin.system.exitProcess

private val supportedStorageEventTypes = setOf(
    StorageEventType.PUT_OBJECT,
    StorageEventType.CREATE_OBJECT,
    StorageEventType.COMPLETE_MULTIPART_UPLOAD,
    StorageEventType.RESTORE_OBJECT,
    StorageEventType.DELETE_OBJECT
)

/**
 * Processor that listens for storage mutations on files that contain RDF data
 * and transforms these into Kvasir change events (modifying the pod KG)
 */
@ApplicationScoped
class RDFStorageMutationListener(
    private val s3Client: S3AsyncClient,
    private val podConfigProvider: PodConfigProvider,
    @param:Channel(Channels.CHANGES_INCOMING_PUBLISH)
    private val emitter: MutinyEmitter<ChangeRequest>,
    @param:ConfigProperty(name = "kvasir-ext.simple-rdf-processor.shutdown-on-error", defaultValue = "true")
    private val shutdownOnError: Boolean
) {

    @Incoming(Channels.STORAGE_EVENTS_SUBSCRIBE)
    fun consumeAndLog(message: Message<StorageEvent>): CompletionStage<Void> {
        return if (!message.payload.eventType.mutation) {
            message.ack()
        } else {
            podConfigProvider.getPodConfigById(message.payload.podId)
                .map { it?.autoIngestRdf() ?: false }
                .chain { autoIngestRdfEnabled ->
                    if (autoIngestRdfEnabled) {
                        val event = message.payload
                        val bucketId = event.sliceId?.let { S3Utils.getBucket(it) } ?: S3Utils.getBucket(event.podId)
                        // If the event operation type is DELETE_OBJECT, look up the previous version
                        (if (event.eventType == StorageEventType.DELETE_OBJECT) {
                            lookupPreviousVersionId(bucketId, event.objectId)
                        } else {
                            Uni.createFrom().item(event.versionId)
                        })
                            .chain { versionId ->
                                // Lookup object contentType
                                s3Client.getObjectContentType(bucketId, event.objectId, versionId)
                                    .chain { rawContentType ->
                                        val contentType =
                                            MediaType.valueOf(rawContentType).let { "${it.type}/${it.subtype}" }
                                        val isSupportedRDFObject = RDFMediaTypes.supportedTypes.contains(contentType)

                                        // Now we have all the parameters, and we can map the event to a change request if applicable
                                        if (isSupportedRDFObject && supportedStorageEventTypes.contains(event.eventType)) {
                                            val changeRequest = mapToChangeRequest(event, versionId)
                                            // Emit the change request
                                            emitter.sendMessage(KafkaRecord.of(changeRequest.podId, changeRequest))
                                        } else {
                                            // No change request should be produced
                                            Uni.createFrom().voidItem()
                                        }
                                    }
                            }
                    } else {
                        Uni.createFrom().voidItem()
                    }
                }
                .onFailure().invoke { err ->
                    Log.error("Error in simple rdf processor${if (shutdownOnError) ", shutting down." else ""}", err)
                    if (shutdownOnError) {
                        exitProcess(1)
                    }
                }
                .eventually {
                    // Always ack the incoming event
                    message.ack().toUni()
                }
                .convert().toCompletionStage()
        }
    }

    private fun lookupPreviousVersionId(bucketId: String, objectId: String): Uni<String?> {
        return Uni.createFrom().completionStage {
            s3Client.listObjectVersions(
                ListObjectVersionsRequest.builder().bucket(bucketId).prefix(objectId).build()
            )
        }
            .map { resp ->
                // Find the version just before the deleted one (the one with the highest lastModified date)
                resp.versions().takeIf { it.isNotEmpty() }?.maxBy { it.lastModified() }
                    ?.versionId()
            }
    }

    private fun mapToChangeRequest(event: StorageEvent, overrideVersionId: String?): ChangeRequest {
        // Transform the object into a Kvasir change request
        val changesBaseUri =
            event.externalObjectUri.split("/").take(4).joinToString("/", postfix = "/changes")
        val id = ChangeRequestId.generate(event.requestingUser).encode()
        val s3Ref = S3Reference(
            event.objectId,
            overrideVersionId ?: event.versionId
        )

        return when (event.eventType) {
            StorageEventType.PUT_OBJECT, StorageEventType.CREATE_OBJECT, StorageEventType.COMPLETE_MULTIPART_UPLOAD, StorageEventType.RESTORE_OBJECT -> ChangeRequest(
                id = id,
                requestingUser = event.requestingUser,
                podId = event.podId,
                insertFromRefs = listOf(s3Ref)
            )

            StorageEventType.DELETE_OBJECT -> ChangeRequest(
                id = id,
                requestingUser = event.requestingUser,
                podId = event.podId,
                deleteFromRefs = listOf(s3Ref)
            )

            else -> {
                // Should never occur, as we filter for supported event types earlier
                throw IllegalArgumentException("Unsupported storage event type: ${event.eventType}, ignoring.")
            }
        }
    }

}