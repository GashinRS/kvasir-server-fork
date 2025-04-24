package kvasir.services.init

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
import jakarta.enterprise.inject.Instance
import kvasir.definitions.config.KvasirConfig
import kvasir.definitions.kg.*
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.reactive.skipToLast
import kvasir.definitions.reactive.toUni
import kvasir.plugins.kg.clickhouse.ClickhouseInitializer
import kvasir.utils.s3.S3Utils
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.jvm.optionals.getOrNull

@ApplicationScoped
class Initializer(
    @ConfigProperty(name = KvasirConfig.BASE_URI_PROPERTY, defaultValue = KvasirConfig.BASE_URI_DEFAULT)
    private val baseUri: String,
    private val minioClient: MinioAsyncClient,
    private val podStore: PodStore,
    private val dbInitializer: ClickhouseInitializer,
    private val podAuthInitializer: Instance<PodAuthInitializer>
) {

    private val initializationComplete = AtomicBoolean(false)

    fun init(
        @Observes event: StartupEvent,
        config: StaticBootstrapConfig,
    ) {
        // Init system db
        dbInitializer.init()
            .chain { _ ->
                // Init pods based on config
                Multi.createFrom().iterable(config.pods())
                    .onItem().transformToUni { podConfig ->
                        val podId = "${baseUri}${podConfig.name()}"
                        setupS3Bucket(podId)
                            .chain { _ -> setupAuth(podId, podConfig.name(), podConfig.authConfiguration()) }
                            .chain { authConfig -> setupPod(podId, podConfig, authConfig) }
                    }
                    .concatenate()
                    .onCompletion().invoke { initializationComplete.set(true) }
                    .skipToLast()
            }.await().indefinitely()
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
    ): Uni<AuthConfiguration?> {
        return if (suppliedAuthConfig.isEmpty && podAuthInitializer.isResolvable) {
            Log.debug("Initializing auth configuration for pod '$podId'")
            podAuthInitializer.get().initialize(podId, podName).map { it }
        } else {
            suppliedAuthConfig.getOrNull()?.let { authConfig ->
                Uni.createFrom().item(
                    AuthConfiguration(
                        authConfig.serverUrl(),
                        authConfig.clientId(),
                        authConfig.clientSecret()
                    )
                )
            } ?: Uni.createFrom().nullItem()
        }
    }

    private fun setupPod(podId: String, podConfig: StaticPodConfig, authConfig: AuthConfiguration?): Uni<Void> {
        Log.debug("Initializing database entry for pod '$podId'")
        return podStore.persist(
            Pod(
                podId,
                listOfNotNull(
                    authConfig?.let { KvasirVocab.authConfiguration to authConfig },
                    PodConfigurationProperty.DEFAULT_CONTEXT to Json.encode(podConfig.defaultContext()),
                    PodConfigurationProperty.AUTO_INGEST_RDF to podConfig.autoIngestRDF()
                ).toMap()
            )
        )
    }

    fun isInitialized(): Boolean = initializationComplete.get()
}