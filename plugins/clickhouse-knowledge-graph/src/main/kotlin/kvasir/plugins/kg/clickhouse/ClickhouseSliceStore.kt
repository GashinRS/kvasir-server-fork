package kvasir.plugins.kg.clickhouse

import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.Slice
import kvasir.definitions.kg.SliceStore
import kvasir.definitions.kg.SliceSummary

@ApplicationScoped
class ClickhouseSliceStore : SliceStore {
    override fun persist(segment: Slice): Uni<Void> {
        TODO("Not yet implemented")
    }

    override fun list(podId: String): Uni<List<SliceSummary>> {
        TODO("Not yet implemented")
    }

    override fun getById(segmentId: String): Uni<Slice> {
        TODO("Not yet implemented")
    }

    override fun deleteById(segmentId: String): Uni<Void> {
        TODO("Not yet implemented")
    }
}