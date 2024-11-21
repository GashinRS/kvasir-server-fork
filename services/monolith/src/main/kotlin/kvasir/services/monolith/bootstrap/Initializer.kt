package kvasir.services.monolith.bootstrap

import io.minio.BucketExistsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.SetBucketVersioningArgs
import io.minio.messages.VersioningConfiguration
import io.quarkus.logging.Log
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.event.Observes
import kvasir.definitions.kg.Pod
import kvasir.definitions.kg.PodConfigurationProperty
import kvasir.definitions.kg.PodStore
import kvasir.utils.s3.S3Utils
import org.eclipse.microprofile.config.inject.ConfigProperty

class Initializer(
    @ConfigProperty(name = "kvasir.base-uri", defaultValue = "http://localhost:8080/")
    private val baseUri: String
) {

    fun init(
        @Observes event: StartupEvent,
        config: StaticBootstrapConfig,
        minioClient: MinioClient,
        podStore: PodStore
    ) {
        config.pods().forEach { podConfig ->
            val podId = "${baseUri}${podConfig.name()}"
            val bucketId = S3Utils.getBucket(podId)
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketId).build())) {
                Log.debug("Creating bucket '$bucketId' for pod '$podId'")
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketId).build())
                minioClient.setBucketVersioning(
                    SetBucketVersioningArgs.builder().bucket(bucketId).config(
                        VersioningConfiguration(VersioningConfiguration.Status.ENABLED, false)
                    ).build()
                )
            }

            Log.debug("Initializing database entry for pod '$podId'")
            podStore.persist(
                Pod(
                    podId,
                    mapOf(PodConfigurationProperty.DEFAULT_CONTEXT to podConfig.defaultContext())
                )
            ).await().indefinitely()
        }
    }

}