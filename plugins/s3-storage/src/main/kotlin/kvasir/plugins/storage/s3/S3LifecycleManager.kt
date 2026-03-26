package kvasir.plugins.storage.s3

import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kvasir.definitions.reactive.skipToLast
import kvasir.definitions.reactive.toMulti
import kvasir.definitions.reactive.toUni
import kvasir.definitions.storage.StorageLifecycleManager
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.*

@ApplicationScoped
class S3LifecycleManager : StorageLifecycleManager {

    @Inject
    lateinit var s3AsyncClient: S3AsyncClient

    override fun initializeForPod(podId: String): Uni<Void> {
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

    override fun cleanupForPod(podId: String): Uni<Void> {
        val bucketId = S3Utils.getBucket(podId)
        Log.debug("Deleting S3 bucket '$bucketId' for Pod '$podId'")
        // First delete all objects (and versions) in the bucket
        return s3AsyncClient.listObjectVersionsPaginator(
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
                ).toUni().replaceWithVoid()
            }
    }
}