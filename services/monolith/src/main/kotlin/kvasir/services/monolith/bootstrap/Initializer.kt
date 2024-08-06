package kvasir.services.monolith.bootstrap

import io.minio.BucketExistsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.event.Observes

class Initializer {

    fun init(@Observes event: StartupEvent, config: StaticBootstrapConfig, minioClient: MinioClient) {
        config.pods().forEach { podConfig ->
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(podConfig.name()).build())) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(podConfig.name()).build())
            }
        }
    }

}