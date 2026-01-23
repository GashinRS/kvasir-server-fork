package kvasir.plugins.kg.clickhouse

import io.quarkus.logging.Log
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.persistence.PersistentEntity
import kvasir.definitions.persistence.StorageLifecycleManager
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
) : StorageLifecycleManager {

    override fun init(detectedEntities: Set<Class<out PersistentEntity>>): Uni<Void> {
        Log.debug("Making sure a Clickhouse schema exists for detected system-level entities $detectedEntities...")
        return Multi.createFrom().iterable(detectedEntities)
            .onItem().transformToUniAndConcatenate { entityClass ->
                val (_, collectionName) = parsePersistentAnnotation(entityClass)
                templateHelper.setupRepository(SYSTEM_DB, collectionName)
            }
            .skipToLast()
    }

    override fun initializePodSchema(podId: String, detectedEntities: Set<Class<out PersistentEntity>>): Uni<Void> {
        Log.debug("Making sure a Clickhouse schema exists for pod $podId...")
        return templateHelper.setupPodDB(podId)
            .chain { _ ->
                Log.debug("Making sure a Clickhouse schema exists for detected pod-level (podId: $podId) entities $detectedEntities...")
                Multi.createFrom().iterable(detectedEntities)
                    .onItem().transformToUniAndConcatenate { entityClass ->
                        val (_, collectionName) = parsePersistentAnnotation(entityClass)
                        templateHelper.setupRepository(databaseFromPodId(podId), collectionName)
                    }
                    .skipToLast()
            }
    }

    override fun dropPodDatabase(podId: String): Uni<Void> {
        return clickhouseClient
            .execute("DROP DATABASE IF EXISTS `${databaseFromPodId(podId)}`")
    }

}