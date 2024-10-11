package kvasir.services.storage.processors.rdf

import io.minio.GetObjectArgs
import io.minio.MinioAsyncClient
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.HttpHeaders
import kvasir.definitions.config.StaticBootstrapConfig
import kvasir.definitions.kg.ChangeRequest
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.definitions.storage.StorageMutationEvent
import kvasir.definitions.storage.StorageMutationEventType
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Outgoing

/**
 * Processor that listens for storage mutations on files that contain RDF data
 * and transforms these into Kvasir change events (modifying the pod KG)
 */
@ApplicationScoped
class RDFStorageMutationListener(
    private val minioClient: MinioAsyncClient,
    private val staticBootstrapConfig: StaticBootstrapConfig
) {

    @Incoming("storage_mutations_subscribe")
    @Outgoing("change_requests_publish")
    fun consumeAndLog(storageMutationEvents: Multi<StorageMutationEvent>): Multi<ChangeRequest> {
        return storageMutationEvents
            .filter { event ->
                // Only trigger this pipeline for pods that have auto-ingestion enabled
                staticBootstrapConfig.pods().find { it.name() == event.podId }?.autoIngestRDF() == true
            }
            .onItem()
            .transformToUniAndConcatenate { event ->
                Uni.createFrom().completionStage(
                    minioClient.getObject(
                        GetObjectArgs.builder().bucket(event.podId).`object`(event.objectId).versionId(event.versionId)
                            .build()
                    )
                ).map { resp -> Pair(event, resp) }
            }
            .filter { (_, resp) ->
                // Only process objects that are RDF data
                RDFMediaTypes.supportedTypes.contains(resp.headers()[HttpHeaders.CONTENT_TYPE])
            }
            .map { (event, _) ->
                // Transform the object into a Kvasir change request
                when (event.mutationType) {
                    StorageMutationEventType.PUT_OBJECT, StorageMutationEventType.COMPLETE_MULTIPART_UPLOAD, StorageMutationEventType.RESTORE_OBJECT -> ChangeRequest(
                        podId = event.podId,
                        insertFromRefs = listOf(
                            mapOf(
                                JsonLdKeywords.type to KvasirVocab.S3Reference,
                                KvasirVocab.Key to event.objectId
                            )
                        ),
                        deleteFromRefs = emptyList()
                    )

                    StorageMutationEventType.DELETE_OBJECT -> ChangeRequest(
                        podId = event.podId,
                        insertFromRefs = emptyList(),
                        deleteFromRefs = listOf(
                            mapOf(
                                JsonLdKeywords.type to KvasirVocab.S3Reference,
                                KvasirVocab.Key to event.objectId
                            )
                        )
                    )
                }
            }
    }

}