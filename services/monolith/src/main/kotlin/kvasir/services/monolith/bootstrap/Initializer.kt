package kvasir.services.monolith.bootstrap

import io.minio.BucketExistsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioAsyncClient
import io.minio.SetBucketVersioningArgs
import io.minio.messages.VersioningConfiguration
import io.quarkus.logging.Log
import io.quarkus.runtime.StartupEvent
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.vertx.core.json.Json
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import kvasir.definitions.kg.*
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.reactive.skipToLast
import kvasir.definitions.reactive.toUni
import kvasir.utils.s3.S3Utils
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

@ApplicationScoped
class Initializer(
    @ConfigProperty(name = "kvasir.base-uri", defaultValue = "http://localhost:8080/")
    private val baseUri: String,
    private val minioClient: MinioAsyncClient,
    private val podStore: PodStore,
    private val podAuthInitializer: PodAuthInitializer
) {

    private val initializationComplete = AtomicBoolean(false)

    fun init(
        @Observes event: StartupEvent,
        config: StaticBootstrapConfig,
    ) {
        Multi.createFrom().iterable(config.pods())
            .onItem().transformToUni { podConfig ->
                val podId = "${baseUri}${podConfig.name()}"
                setupS3Bucket(podId)
                    .chain { _ -> setupAuth(podId, podConfig.name(), podConfig.authConfiguration()) }
                    .chain { authConfig -> setupPod(podId, podConfig, authConfig) }
            }
            .concatenate()
            .onCompletion().invoke { initializationComplete.set(true) }
            .skipToLast().await()
            .indefinitely()
    }

    private fun setupS3Bucket(podId: String): Uni<Void> {
        val bucketId = S3Utils.getBucket(podId)
        return minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketId).build()).toUni()
            .chain { bucketExists ->
                if (!bucketExists) {
                    Log.debug("Creating bucket '$bucketId' for pod '$podId'")
                    minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketId).build()).toUni()
                        .chain { _ ->
                            minioClient.setBucketVersioning(
                                SetBucketVersioningArgs.builder().bucket(bucketId).config(
                                    VersioningConfiguration(VersioningConfiguration.Status.ENABLED, false)
                                ).build()
                            ).toUni()
                        }
                } else {
                    Uni.createFrom().voidItem()
                }
            }
    }

    private fun setupAuth(
        podId: String,
        podName: String,
        suppliedAuthConfig: Optional<AuthConfigurationConfig>
    ): Uni<AuthConfiguration> {
        return if (suppliedAuthConfig.isEmpty) {
            Log.debug("Initializing auth configuration for pod '$podId'")
            podAuthInitializer.initialize(podId, podName)
        } else {
            Uni.createFrom().item(
                AuthConfiguration(
                    suppliedAuthConfig.get().serverUrl(),
                    suppliedAuthConfig.get().clientId(),
                    suppliedAuthConfig.get().clientSecret()
                )
            )
        }
    }

    private fun setupPod(podId: String, podConfig: StaticPodConfig, authConfig: AuthConfiguration): Uni<Void> {
        Log.debug("Initializing database entry for pod '$podId'")
        return podStore.persist(
            Pod(
                podId,
                listOfNotNull(
                    KvasirVocab.authConfiguration to authConfig,
                    PodConfigurationProperty.DEFAULT_CONTEXT to Json.encode(podConfig.defaultContext()),
                    PodConfigurationProperty.AUTO_INGEST_RDF to podConfig.autoIngestRDF()
                ).toMap()
            )
        )
    }

    fun isInitialized(): Boolean = initializationComplete.get()
}