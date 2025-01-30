package kvasir.plugins.kg.clickhouse

import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.kg.slices.SliceStore
import kvasir.definitions.kg.slices.SliceSummary
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.specs.SLICE_TABLE
import kvasir.plugins.kg.clickhouse.specs.SYSTEM_DB
import kvasir.plugins.kg.clickhouse.specs.SliceInsertRecordSpec
import kvasir.plugins.kg.clickhouse.specs.SliceQuerySpec

@ApplicationScoped
class ClickhouseSliceStore(private val clickhouseClient: ClickhouseClient) : SliceStore {
    override fun persist(segment: Slice): Uni<Void> {
        return clickhouseClient.insert(SliceInsertRecordSpec, listOf(segment))
    }

    override fun list(podId: String): Uni<List<SliceSummary>> {
        return clickhouseClient.query(
            SliceQuerySpec(),
            "SELECT id, argMax(json, timestamp) FROM $SLICE_TABLE WHERE pod_id = '$podId' GROUP BY id"
        )
            .map { results ->
                results.map { result ->
                    SliceSummary(
                        id = result.id,
                        name = result.name,
                        description = result.description
                    )
                }
            }
    }

    override fun getById(podId: String, segmentId: String): Uni<Slice?> {
        return clickhouseClient.query(
            SliceQuerySpec(),
            "SELECT id, argMax(json, timestamp) FROM $SLICE_TABLE WHERE id = '$segmentId' AND pod_id = '$podId' GROUP BY id"
        )
            .map { results ->
                results.firstOrNull()
            }
    }

    override fun deleteById(podId: String, segmentId: String): Uni<Void> {
        return clickhouseClient.execute("ALTER TABLE $SLICE_TABLE DELETE WHERE id = '$segmentId' AND pod_id = '$podId'", SYSTEM_DB)
    }
}