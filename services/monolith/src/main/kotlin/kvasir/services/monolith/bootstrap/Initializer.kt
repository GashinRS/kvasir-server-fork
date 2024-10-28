package kvasir.services.monolith.bootstrap

import io.minio.BucketExistsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.event.Observes
import kvasir.definitions.kg.Pod
import kvasir.definitions.kg.PodConfigurationProperty
import kvasir.definitions.kg.PodStore

class Initializer {

    fun init(
        @Observes event: StartupEvent,
        config: StaticBootstrapConfig,
        minioClient: MinioClient,
        podStore: PodStore
    ) {
        config.pods().forEach { podConfig ->
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(podConfig.name()).build())) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(podConfig.name()).build())
            }

            podStore.persist(
                Pod(
                    podConfig.name(),
                    mapOf(PodConfigurationProperty.DEFAULT_CONTEXT to podConfig.defaultContext())
                )
            ).await().indefinitely()
        }
    }

}