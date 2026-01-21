package kvasir.definitions.persistence

import io.smallrye.mutiny.Uni

interface StorageLifecycleManager {

    fun init(detectedEntities: Set<Class<out PersistentEntity>>): Uni<Void>

    fun initializePodSchema(podId: String, detectedEntities: Set<Class<out PersistentEntity>>): Uni<Void>

    fun dropPodDatabase(podId: String): Uni<Void>

}