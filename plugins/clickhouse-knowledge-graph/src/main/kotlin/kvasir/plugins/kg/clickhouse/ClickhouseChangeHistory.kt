package kvasir.plugins.kg.clickhouse

import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.changes.ChangeHistory
import kvasir.definitions.kg.changes.ChangeHistoryFactory
import kvasir.definitions.kg.changes.ChangeReport
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.persistence.AbstractRepository
import kvasir.plugins.kg.clickhouse.specs.EntityQuerySpec
import kvasir.plugins.kg.clickhouse.specs.EntityWriteSpec
import kvasir.plugins.kg.clickhouse.utils.databaseFromPodId

@ApplicationScoped
class ClickhouseChangeHistoryFactory(private val clickhouseClient: ClickhouseClient) : ChangeHistoryFactory {
    override fun getChangeHistory(podId: String): ChangeHistory {
        return ClickhouseChangeHistory(clickhouseClient, podId)
    }

}

class ClickhouseChangeHistory(
    clickhouseClient: ClickhouseClient,
    podId: String
) : AbstractRepository<ChangeReport>(
    clickhouseClient,
    EntityQuerySpec(ChangeReport::class, databaseFromPodId(podId), "changelog"),
    EntityWriteSpec(ChangeReport::class, databaseFromPodId(podId), "changelog")
), ChangeHistory