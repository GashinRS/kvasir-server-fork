package kvasir.services.storage.processors.rdf

import io.minio.GetObjectArgs
import io.minio.ListObjectsArgs
import io.minio.MinioAsyncClient
import io.minio.messages.Item
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.vertx.mutiny.core.Vertx
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.HttpHeaders
import kvasir.definitions.kg.ChangeRequest
import kvasir.definitions.kg.PodStore
import kvasir.definitions.messaging.Channels
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.definitions.storage.StorageMutationEvent
import kvasir.definitions.storage.StorageMutationEventType
import kvasir.utils.idgen.ChangeRequestId
import kvasir.utils.s3.S3Utils
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Outgoing
import java.util.*

/**
 * Processor that listens for storage mutations on files that contain RDF data
 * and transforms these into Kvasir change events (modifying the pod KG)
 */
@ApplicationScoped
class RDFStorageMutationListener(
    private val minioClient: MinioAsyncClient,
    private val podStore: PodStore,
    private val vertx: Vertx
) {

    @Incoming(Channels.STORAGE_MUTATIONS_SUBSCRIBE)
    @Outgoing(Channels.CHANGE_REQUESTS_PUBLISH)
    fun consumeAndLog(storageMutationEvents: Multi<StorageMutationEvent>): Multi<ChangeRequest> {
        return storageMutationEvents
            .onItem().transformToUniAndConcatenate { event ->
                podStore.getById(event.podId).map { event to (it?.getAutoIngestRDF() == true) }
            }
            .filter { (_, autoIngestEnabled) -> autoIngestEnabled }
            .map { (event, _) -> event }
            .onItem()
            .transformToUniAndConcatenate { event ->
                val bucketId = event.sliceId?.let { S3Utils.getBucket(it) } ?: S3Utils.getBucket(event.podId)

                // If the operation is of type DELETE_OBJECT, we need to look up the version previous to the deletion
                if (event.mutationType == StorageMutationEventType.DELETE_OBJECT) {
                    vertx.executeBlocking {
                        val versions: List<io.minio.Result<Item>> = minioClient.listObjects(
                            ListObjectsArgs.builder().bucket(bucketId).prefix(event.objectId)
                                .includeVersions(true).build()
                        ).toList().sortedByDescending { it.get().lastModified() }
                        versions.find { it.get().versionId() == event.versionId }?.let {
                            val previousVersion = versions[versions.indexOf(it) + 1]
                            event.copy(versionId = previousVersion.get().versionId())
                        }
                    }
                } else {
                    Uni.createFrom().item(event)
                }
            }
            .onItem()
            .transformToUniAndConcatenate { event ->
                val bucketId = event.sliceId?.let { S3Utils.getBucket(it) } ?: S3Utils.getBucket(event.podId)
                Uni.createFrom().completionStage(
                    minioClient.getObject(
                        GetObjectArgs.builder().bucket(bucketId).`object`(event.objectId).versionId(event.versionId)
                            .build()
                    )
                ).map { resp ->
                    // Copy headers and then close the response
                    resp.use {
                        event to it.headers()
                    }
                }
            }
            .filter { (_, headers) ->
                // Only process objects that are RDF data
                RDFMediaTypes.supportedTypes.contains(headers[HttpHeaders.CONTENT_TYPE])
            }
            .map { (event, _) ->
                // Transform the object into a Kvasir change request
                val id = ChangeRequestId.generate(event.externalObjectUri.substringBefore("/s3") + "/changes").encode()
                when (event.mutationType) {
                    StorageMutationEventType.PUT_OBJECT, StorageMutationEventType.COMPLETE_MULTIPART_UPLOAD, StorageMutationEventType.RESTORE_OBJECT -> ChangeRequest(
                        id = id,
                        podId = event.podId,
                        insertFromRefs = listOf(
                            mapOf(
                                JsonLdKeywords.type to KvasirVocab.S3Reference,
                                KvasirVocab.key to event.objectId,
                                KvasirVocab.versionId to event.versionId
                            )
                        ),
                        deleteFromRefs = emptyList()
                    )

                    StorageMutationEventType.DELETE_OBJECT -> ChangeRequest(
                        id = id,
                        podId = event.podId,
                        insertFromRefs = emptyList(),
                        deleteFromRefs = listOf(
                            mapOf(
                                JsonLdKeywords.type to KvasirVocab.S3Reference,
                                KvasirVocab.key to event.objectId,
                                KvasirVocab.versionId to event.versionId
                            )
                        )
                    )
                }
            }
    }

}