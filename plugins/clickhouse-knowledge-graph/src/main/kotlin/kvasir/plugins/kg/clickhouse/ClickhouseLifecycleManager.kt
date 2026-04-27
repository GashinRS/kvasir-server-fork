package kvasir.plugins.kg.clickhouse

import io.quarkus.logging.Log
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.changes.ChangeRecordBackendLifecycleManager
import kvasir.definitions.persistence.PersistentEntity
import kvasir.definitions.persistence.RepositoriesLifecycleManager
import kvasir.definitions.reactive.skipToLast
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.schema.QuteSchemaTemplateHelper
import kvasir.plugins.kg.clickhouse.specs.SYSTEM_DB
import kvasir.plugins.kg.clickhouse.utils.databaseFromPodId
import kvasir.plugins.kg.clickhouse.utils.parsePersistentAnnotation

@ApplicationScoped
class ClickhouseLifecycleManager(
    private val templateHelper: QuteSchemaTemplateHelper,
    private val clickhouseClient: ClickhouseClient
) : ChangeRecordBackendLifecycleManager, RepositoriesLifecycleManager {

    /**
     * Initialize Clickhouse schemas for the given system-level persistent entities.
     */
    override fun initialize(detectedEntities: Set<Class<out PersistentEntity>>): Uni<Void> {
        Log.debug("Making sure a Clickhouse schema exists for detected system-level entities $detectedEntities...")
        return Multi.createFrom().iterable(detectedEntities)
            .onItem().transformToUniAndConcatenate { entityClass ->
                val (_, collectionName, _, versioned) = parsePersistentAnnotation(entityClass)
                templateHelper.setupRepository(SYSTEM_DB, collectionName, versioned)
            }
            .skipToLast()
    }

    /**
     * Initialize Clickhouse schemas for the given pod-level persistent entities for a specific pod.
     */
    override fun initializeForPod(podId: String, detectedEntities: Set<Class<out PersistentEntity>>): Uni<Void> {
        Log.debug("Making sure a Clickhouse schema exists for detected pod-level (podId: $podId) entities $detectedEntities...")
        return Multi.createFrom().iterable(detectedEntities)
            .onItem().transformToUniAndConcatenate { entityClass ->
                val (_, collectionName, _, versioned) = parsePersistentAnnotation(entityClass)
                templateHelper.setupRepository(databaseFromPodId(podId), collectionName, versioned)
            }
            .skipToLast()
    }

    /**
     * Cleanup Clickhouse schemas for the given system-level persistent entities.
     * Removes the tables related to the entities, deleting the data.
     */
    override fun cleanup(detectedEntities: Set<Class<out PersistentEntity>>): Uni<Void> {
        Log.debug("Dropping Clickhouse tables for detected system-level entities $detectedEntities...")
        return Multi.createFrom().iterable(detectedEntities)
            .onItem().transformToUniAndConcatenate { entityClass ->
                val (_, collectionName) = parsePersistentAnnotation(entityClass)
                clickhouseClient.execute("DROP TABLE IF EXISTS `${SYSTEM_DB}`.`${collectionName}`")
            }
            .skipToLast()
    }

    /**
     * Cleanup Clickhouse schemas for the given pod-level persistent entities for a specific pod.
     * Removes the pod-specific database, deleting all data related to the pod.
     * Might overlap with the ChangeRecordBackendLifecycleManager.cleanupForPod implementation, but this is acceptable.
     */
    override fun cleanupForPod(
        podId: String,
        detectedEntities: Set<Class<out PersistentEntity>>
    ): Uni<Void> {
        Log.debug("Dropping pod specific Clickhouse database for pod '$podId'")
        return clickhouseClient
            .execute("DROP DATABASE IF EXISTS `${databaseFromPodId(podId)}`")
    }

    /**
     * Initialize Clickhouse resources for the ChangeRecordBackend for a specific pod.
     */
    override fun initializeForPod(podId: String): Uni<Void> {
        Log.debug("Making sure a Clickhouse schema exists for pod $podId...")
        return templateHelper.setupPodDB(podId)
    }

    /**
     * Cleanup Clickhouse resources for the ChangeRecordBackend for a specific pod.
     * Deletes the pod-specific database.
     * Might overlap with the RepositoriesLifecycleManager.cleanupForPod implementation, but this is acceptable.
     */
    override fun cleanupForPod(podId: String): Uni<Void> {
        Log.debug("Dropping pod specific Clickhouse database for pod '$podId'")
        return clickhouseClient
            .execute("DROP DATABASE IF EXISTS `${databaseFromPodId(podId)}`")
    }

}