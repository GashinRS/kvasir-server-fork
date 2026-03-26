package kvasir.utils.pod

import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import kvasir.definitions.annotations.StorageLevel
import kvasir.definitions.auth.AuthLifecycleManager
import kvasir.definitions.config.BootstrapPodConfig
import kvasir.definitions.kg.Pod
import kvasir.definitions.kg.changes.ChangeRecordBackendLifecycleManager
import kvasir.definitions.persistence.RepositoriesLifecycleManager
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.storage.StorageLifecycleManager
import kvasir.utils.persistence.PersistentEntityDetector
import kotlin.jvm.optionals.getOrNull

@ApplicationScoped
class PodSetupHelper {

    @Inject
    lateinit var repositoryFactory: RepositoryFactory

    @Inject
    lateinit var podAuthLifecycleManager: Instance<AuthLifecycleManager>

    @Inject
    lateinit var changeRecordBackendLifecycleManager: Instance<ChangeRecordBackendLifecycleManager>

    @Inject
    lateinit var repositoriesLifecycleManager: Instance<RepositoriesLifecycleManager>

    @Inject
    lateinit var storageLifecycleManager: Instance<StorageLifecycleManager>

    @Inject
    lateinit var persistentEntityDetector: PersistentEntityDetector

    fun createPod(
        podId: String,
        bootstrapPodConfig: BootstrapPodConfig,
        rawPodConfig: String,
        errorWhenExists: Boolean = false
    ): Uni<Void> {
        val podStore = repositoryFactory.getRepository(Pod::class)
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
                        .chain { _ ->
                            // Initialize S3 bucket for the Pod
                            if (storageLifecycleManager.isResolvable) {
                                storageLifecycleManager.get().initializeForPod(podId)
                            } else {
                                Uni.createFrom().voidItem()
                            }
                        }
                        .chain { _ ->
                            if (changeRecordBackendLifecycleManager.isResolvable) {
                                // Initialize change record backend for the Pod
                                changeRecordBackendLifecycleManager.get().initializeForPod(podId)
                            } else {
                                Uni.createFrom().voidItem()
                            }
                        }
                        .chain { _ ->
                            if (repositoriesLifecycleManager.isResolvable) {
                                // Initialize storage schema for the Pod
                                repositoriesLifecycleManager.get().initializeForPod(
                                    podId, persistentEntityDetector.getDetectedEntityClasses(
                                        StorageLevel.PER_POD
                                    )
                                )
                            } else {
                                Uni.createFrom().voidItem()
                            }
                        }
                        .chain { _ ->
                            // Initialize the configured auth policy provider for the Pod (if any)
                            if (podAuthLifecycleManager.isResolvable) {
                                val initializer = podAuthLifecycleManager.get()
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
        val podStore = repositoryFactory.getRepository(Pod::class)
        Log.debug("Deleting Pod '$podId' (deleteData=$deleteData)")
        return podStore.deleteById(podId)
            .chain { _ ->
                if (deleteData && repositoriesLifecycleManager.isResolvable) {
                    repositoriesLifecycleManager.get().cleanupForPod(
                        podId, persistentEntityDetector.getDetectedEntityClasses(
                            StorageLevel.PER_POD
                        )
                    )
                } else {
                    Uni.createFrom().voidItem()
                }
            }
            .chain { _ ->
                // Clean up Change Record Backend (if any)
                if (deleteData && changeRecordBackendLifecycleManager.isResolvable) {
                    changeRecordBackendLifecycleManager.get().cleanupForPod(podId)
                } else {
                    Uni.createFrom().voidItem()
                }
            }
            .chain { _ ->
                // CLear up S3 bucket (if deleteData is true) and storage lifecycle manager is available
                if (deleteData && storageLifecycleManager.isResolvable) {
                    storageLifecycleManager.get().cleanupForPod(podId)
                } else {
                    Uni.createFrom().voidItem()
                }
            }
            .chain { _ ->
                // CLear up Auth model (if any)
                if (podAuthLifecycleManager.isResolvable) {
                    val initializer = podAuthLifecycleManager.get()
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
}