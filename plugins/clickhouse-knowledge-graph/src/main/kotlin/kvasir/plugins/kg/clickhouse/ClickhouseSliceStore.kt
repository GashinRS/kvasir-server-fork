package kvasir.plugins.kg.clickhouse

import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.kg.slices.SliceStore
import kvasir.definitions.kg.slices.SliceStoreFactory
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.persistence.AbstractRepository
import kvasir.plugins.kg.clickhouse.specs.EntityQuerySpec
import kvasir.plugins.kg.clickhouse.specs.EntityWriteSpec
import kvasir.plugins.kg.clickhouse.utils.databaseFromPodId

@ApplicationScoped
class ClickhouseSliceStoreFactory(private val clickhouseClient: ClickhouseClient) : SliceStoreFactory {
    override fun getSliceStore(podId: String): SliceStore {
        return ClickhouseSliceStore(clickhouseClient, podId)
    }
}

class ClickhouseSliceStore(clickhouseClient: ClickhouseClient, podId: String) : AbstractRepository<Slice>(
    clickhouseClient,
    EntityQuerySpec(Slice::class, databaseFromPodId(podId), "slices"),
    EntityWriteSpec(Slice::class, databaseFromPodId(podId), "slices")
), SliceStore