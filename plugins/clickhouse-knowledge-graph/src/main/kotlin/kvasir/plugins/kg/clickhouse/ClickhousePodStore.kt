package kvasir.plugins.kg.clickhouse

import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.Pod
import kvasir.definitions.kg.PodStore
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.specs.POD_TABLE
import kvasir.plugins.kg.clickhouse.specs.PodInsertRecordSpec
import kvasir.plugins.kg.clickhouse.specs.PodQuerySpec
import kvasir.plugins.kg.clickhouse.specs.SYSTEM_DB

@ApplicationScoped
class ClickhousePodStore(
    private val clickhouseClient: ClickhouseClient,
    private val clickhouseInitializer: ClickhouseInitializer
) : PodStore {
    override fun persist(pod: Pod): Uni<Void> {
        // If the pod is persisted for the first time, initialize the pod's databases
        return getById(pod.id)
            .chain { existingPod ->
                if (existingPod == null) {
                    clickhouseInitializer.initializePodSchema(pod.id)
                } else {
                    Uni.createFrom().voidItem()
                }
            }
            .chain { _ -> clickhouseClient.insert(PodInsertRecordSpec, listOf(pod)) }
    }

    override fun list(): Uni<List<Pod>> {
        return clickhouseClient.query(PodQuerySpec(), "SELECT id, argMax(json, timestamp) FROM $POD_TABLE GROUP BY id")
    }

    override fun getById(id: String): Uni<Pod?> {
        return clickhouseClient.query(
            PodQuerySpec(),
            "SELECT id, argMax(json, timestamp) FROM $POD_TABLE WHERE id = '$id' GROUP BY id"
        )
            .map { results -> results.firstOrNull() }
    }

    override fun deleteById(id: String): Uni<Void> {
        return clickhouseClient.execute("ALTER TABLE $POD_TABLE DELETE WHERE id = '$id'", SYSTEM_DB)
    }
}