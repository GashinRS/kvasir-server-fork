package kvasir.utils.pod

import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor
import com.github.jsonldjava.utils.JsonUtils
import io.minio.BucketExistsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioAsyncClient
import io.minio.SetBucketVersioningArgs
import io.minio.messages.VersioningConfiguration
import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import kvasir.definitions.auth.AuthInitializer
import kvasir.definitions.config.PodConfig
import kvasir.definitions.kg.Pod
import kvasir.definitions.kg.PodStore
import kvasir.definitions.kg.PodStoreFactory
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.utils.s3.S3Utils
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

const val PLAIN_JSON_VOCAB = "urn:kvasir:plain-json:"

abstract class PodSetupHelper {

    @Inject
    protected lateinit var podStoreFactory: PodStoreFactory

    @Inject
    protected lateinit var minioClient: MinioAsyncClient

    @Inject
    protected lateinit var podAuthInitializer: Instance<AuthInitializer>

    fun createPod(podId: String, podConfig: PodConfig, errorWhenExists: Boolean = false): Uni<Void> {
        val podStore = podStoreFactory.createPodStore()
        return podStore.findById(podId)
            .chain { existingPod ->
                // Pod exists already, ...
                if (existingPod != null) {
                    if (errorWhenExists) {
                        Uni.createFrom().failure(IllegalStateException("Pod with ID $podId already exists."))
                    }
                    // ... but in dev mode the OpenFga store will be empty
                    else {
                        // Be sure Bucket exists: try setting up s3 bucket (will not do anything if bucketName already exists)
                        createS3BucketIfNotExist(podId)
                            .chain { _ ->
                                if (podAuthInitializer.isResolvable) {
                                    val initializer = podAuthInitializer.get()
                                    Log.debug("Try initializing configured auth policy provider (${initializer::class.java.name}) for Pod '$podId'")
                                    // Be sure PodAuthModel exists: try initializeForPod (will not do anything if store with podName already exists)
                                    initializer.initializeForPod(
                                        podId,
                                        podConfig.name(),
                                        podConfig.ownerUserId()
                                            .orElse(podConfig.name()), // Owner ID is the same as Pod ID for simplicity
                                        existingPod,
                                        podConfig.generateClients().getOrNull()
                                    )
                                } else Uni.createFrom().voidItem()
                            }
                    }
                } else {
                    // Create storage entry for the new Pod
                    Log.debug("Adding storage entry for Pod '$podId'")
                    val newPod = Pod(podId, parseConfiguration(podConfig.configuration()))
                    podStore.persist(newPod)
                        .chain { _ -> createS3BucketIfNotExist(podId) }
                        .chain { _ ->
                            // Initialize the configured auth policy provider for the Pod (if any)
                            if (podAuthInitializer.isResolvable) {
                                val initializer = podAuthInitializer.get()
                                Log.debug("Initializing configured auth policy provider (${initializer::class.java.name}) for Pod '$podId'")
                                initializer.initializeForPod(
                                    podId,
                                    podConfig.name(),
                                    podConfig.ownerUserId()
                                        .orElse(podConfig.name()), // Owner ID is the same as Pod ID for simplicity
                                    newPod,
                                    podConfig.generateClients().getOrNull()
                                )
                            } else {
                                Uni.createFrom().voidItem()
                            }
                        }
                }
            }
    }

    fun createS3BucketIfNotExist(podId: String): Uni<Void> {
        val bucketId = S3Utils.getBucket(podId)
        // Initialize a new S3 bucket for the pod
        Log.debug("Creating S3 bucket '$bucketId' for Pod '$podId'")
        return Uni.createFrom()
            .completionStage(minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketId).build()))
            .flatMap { bucketExists ->
                if (bucketExists) Uni.createFrom().voidItem()
                else Uni.createFrom()
                    .completionStage(
                        minioClient.makeBucket(
                            MakeBucketArgs.builder().bucket(bucketId).build()
                        )
                    )
                    .chain { _ ->
                        // Enable versioning for the bucket
                        Uni.createFrom().completionStage(
                            minioClient.setBucketVersioning(
                                SetBucketVersioningArgs.builder()
                                    .bucket(bucketId)
                                    .config(
                                        VersioningConfiguration(
                                            VersioningConfiguration.Status.ENABLED,
                                            true
                                        )
                                    )
                                    .build()
                            )
                        )
                    }
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