package kvasir.utils.pod

import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor
import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import kvasir.definitions.auth.AuthInitializer
import kvasir.definitions.config.BootstrapPodConfig
import kvasir.definitions.kg.Pod
import kvasir.definitions.kg.PodStoreFactory
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.reactive.skipToLast
import kvasir.definitions.reactive.toMulti
import kvasir.definitions.reactive.toUni
import kvasir.utils.s3.S3Utils
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.*
import kotlin.jvm.optionals.getOrNull

const val PLAIN_JSON_VOCAB = "urn:kvasir:plain-json:"

@ApplicationScoped
class PodSetupHelper {

    @Inject
    lateinit var podStoreFactory: PodStoreFactory

    @Inject
    lateinit var s3AsyncClient: S3AsyncClient

    @Inject
    lateinit var podAuthInitializer: Instance<AuthInitializer>

    fun createPod(
        podId: String,
        bootstrapPodConfig: BootstrapPodConfig,
        rawPodConfig: String,
        errorWhenExists: Boolean = false
    ): Uni<Void> {
        val podStore = podStoreFactory.createPodStore()
        return podStore.findById(podId)
            .chain { existingPod ->
                // Pod exists already, ...
                if (existingPod != null) {
                    if (errorWhenExists) {
                        Uni.createFrom().failure(IllegalStateException("Pod with ID $podId already exists."))
                    } else {
                        Log.debug("Pod '$podId' already exists, skipping initialization.")
                        Uni.createFrom().voidItem()
                    }
                } else {
                    // Create storage entry for the new Pod
                    Log.debug("Adding storage entry for Pod '$podId'")
                    val newPod = Pod(podId, rawPodConfig)
                    podStore.persist(newPod)
                        .chain { _ -> createS3BucketIfNotExist(podId) }
                        .chain { _ ->
                            // Initialize the configured auth policy provider for the Pod (if any)
                            if (podAuthInitializer.isResolvable) {
                                val initializer = podAuthInitializer.get()
                                Log.debug("Initializing configured auth policy provider (${initializer::class.java.name}) for Pod '$podId'")
                                initializer.initializeForPod(newPod, bootstrapPodConfig)
                            } else {
                                Uni.createFrom().voidItem()
                            }
                        }
                }
            }
    }

    fun deletePod(
        podId: String,
        bootstrapPodConfig: BootstrapPodConfig,
        deleteData: Boolean,
        deleteOwner: Boolean
    ): Uni<Void> {
        val podStore = podStoreFactory.createPodStore()
        Log.debug("Deleting Pod '$podId' (deleteData=$deleteData)")
        return podStore.deleteById(podId, deleteData)
            .chain { _ ->
                // CLear up S3 bucket (if deleteData is true)
                if (deleteData) {
                    val bucketId = S3Utils.getBucket(podId)
                    Log.debug("Deleting S3 bucket '$bucketId' for Pod '$podId'")
                    // First delete all objects (and versions) in the bucket
                    s3AsyncClient.listObjectVersionsPaginator(
                        ListObjectVersionsRequest.builder().bucket(bucketId).prefix("").build()
                    ).toMulti()
                        .group().intoLists().of(500)
                        .onItem().transformToUniAndConcatenate { batch ->
                            val deletes = batch.flatMap { v ->
                                v.versions()
                                    .map { ObjectIdentifier.builder().key(it.key()).versionId(it.versionId()).build() }
                            }.toList() + batch.flatMap { v ->
                                v.deleteMarkers()
                                    .map { ObjectIdentifier.builder().key(it.key()).versionId(it.versionId()).build() }
                            }.toList()
                            if (deletes.isNotEmpty()) {
                                s3AsyncClient.deleteObjects(
                                    DeleteObjectsRequest.builder().bucket(bucketId)
                                        .delete(Delete.builder().objects(deletes).build()).build()
                                ).toUni().replaceWithVoid()
                            } else {
                                Uni.createFrom().voidItem()
                            }
                        }
                        .skipToLast()
                        .chain { _ ->
                            // Then delete the bucket
                            s3AsyncClient.deleteBucket(
                                DeleteBucketRequest.builder().bucket(bucketId).build()
                            ).toUni()
                        }
                } else {
                    Uni.createFrom().voidItem()
                }
            }
            .chain { _ ->
                // CLear up Auth model (if any)
                if (podAuthInitializer.isResolvable) {
                    val initializer = podAuthInitializer.get()
                    Log.debug("Clearing auth model for Pod '$podId' using provider (${initializer::class.java.name})")
                    initializer.cleanupForPod(
                        podId,
                        bootstrapPodConfig.name(),
                        bootstrapPodConfig.ownerUserId().getOrNull().takeIf { deleteOwner })
                } else {
                    Uni.createFrom().voidItem()
                }
            }
    }

    fun createS3BucketIfNotExist(podId: String): Uni<Void> {
        val bucketId = S3Utils.getBucket(podId)
        // Initialize a new S3 bucket for the pod
        Log.debug("Creating S3 bucket '$bucketId' for Pod '$podId'")
        return Uni.createFrom()
            .completionStage(
                s3AsyncClient.createBucket(
                    CreateBucketRequest.builder().bucket(bucketId).build()
                )
            )
            .chain { _ ->
                // Enable versioning for the bucket
                Uni.createFrom().completionStage(
                    s3AsyncClient.putBucketVersioning(
                        PutBucketVersioningRequest.builder()
                            .bucket(bucketId)
                            .versioningConfiguration(
                                VersioningConfiguration.builder()
                                    .status(BucketVersioningStatus.ENABLED)
                                    .build()
                            )
                            .build()
                    )
                ).replaceWithVoid()
            }
            .onFailure { err -> err is BucketAlreadyOwnedByYouException || err is BucketAlreadyExistsException }
            .recoverWithUni { _ ->
                Log.warn("S3 Bucket '$bucketId' for Pod '$podId' already exists.")
                Uni.createFrom().voidItem()
            }
    }

    /**
     * Parse Pod config into fully-qualified compact JSON-LD form.
     * A side effect of this method is that properties that contain regular JSON objects, become empty objects
     * (if no @vocab is defined) because of the missing context.
     * To avoid this, we perform a workaround by adding a temporary context that defines the @vocab.
     */
    private fun parseConfiguration(configuration: JSONObject): JSONObject {
        val modifiedConfig = configuration.mapValues { (key, value) ->
            if (key != JsonLdKeywords.context && value is Map<*, *> && !value.containsKey(JsonLdKeywords.context)) {
                // Workaround: add temporary context with @vocab to avoid empty objects
                value + mapOf(JsonLdKeywords.context to mapOf(JsonLdKeywords.vocab to PLAIN_JSON_VOCAB))
            } else {
                value
            }
        }
        val result = JsonLdProcessor.compact(
            JsonLdProcessor.expand(modifiedConfig), mapOf(JsonLdKeywords.vocab to PLAIN_JSON_VOCAB),
            JsonLdOptions()
        )
        return result
    }

}