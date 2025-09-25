package kvasir.plugins.kg.clickhouse

import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.Pod
import kvasir.definitions.kg.PodStore
import kvasir.definitions.kg.PodStoreFactory
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.persistence.AbstractRepository
import kvasir.plugins.kg.clickhouse.specs.EntityQuerySpec
import kvasir.plugins.kg.clickhouse.specs.EntityWriteSpec
import kvasir.plugins.kg.clickhouse.specs.SYSTEM_DB
import kvasir.plugins.kg.clickhouse.utils.databaseFromPodId

@ApplicationScoped
class ClickhousePodStoreFactory(
    private val clickhouseClient: ClickhouseClient,
    private val clickhouseInitializer: ClickhouseInitializer
) : PodStoreFactory {
    override fun createPodStore(): PodStore {
        return ClickhousePodStore(clickhouseClient, clickhouseInitializer)
    }

}

class ClickhousePodStore(
    clickhouseClient: ClickhouseClient,
    private val clickhouseInitializer: ClickhouseInitializer
) : AbstractRepository<Pod>(
    clickhouseClient,
    EntityQuerySpec(Pod::class, SYSTEM_DB, "pods"),
    EntityWriteSpec(Pod::class, SYSTEM_DB, "pods")
),
    PodStore {

    override fun persist(entity: Pod): Uni<Void> {
        // If the pod is persisted for the first time, initialize the pod's databases
        return findById(entity.id)
            .chain { existingPod ->
                if (existingPod == null) {
                    clickhouseInitializer.initializePodSchema(entity.id)
                } else {
                    Uni.createFrom().voidItem()
                }
            }
            .chain { _ -> super.persist(entity) }
    }

    override fun deleteById(id: String, deleteData: Boolean): Uni<Void> {
        return super.deleteById(id).chain { _ ->
            if (deleteData) {
                clickhouseClient
                    .execute("DROP DATABASE IF EXISTS `${databaseFromPodId(id)}`")
            } else {
                Uni.createFrom().voidItem()
            }
        }
    }
}