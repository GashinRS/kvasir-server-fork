package kvasir.definitions.storage

import io.smallrye.mutiny.Uni

interface StorageLifecycleManager {

    fun initializeForPod(podId: String): Uni<Void>

    fun cleanupForPod(podId: String): Uni<Void>

}