package kvasir.plugins.kg.clickhouse

import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.NotFoundException
import kvasir.definitions.kg.Slice
import kvasir.definitions.kg.SliceStore
import kvasir.definitions.kg.SliceSummary
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.specs.SLICE_TABLE
import kvasir.plugins.kg.clickhouse.specs.SliceInsertRecordSpec
import kvasir.plugins.kg.clickhouse.specs.SliceQuerySpec

@ApplicationScoped
class ClickhouseSliceStore(private val clickhouseClient: ClickhouseClient) : SliceStore {
    override fun persist(segment: Slice): Uni<Void> {
        return clickhouseClient.insert(SliceInsertRecordSpec(databaseFromPodId(segment.podId)), listOf(segment))
    }

    override fun list(podId: String): Uni<List<SliceSummary>> {
        return clickhouseClient.query(SliceQuerySpec(databaseFromPodId(podId)), "SELECT id, argMax(json, timestamp) FROM $SLICE_TABLE GROUP BY id")
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

    override fun getById(podId: String, segmentId: String): Uni<Slice> {
        return clickhouseClient.query(
            SliceQuerySpec(databaseFromPodId(podId)),
            "SELECT id, argMax(json, timestamp) FROM $SLICE_TABLE WHERE id = '$segmentId' GROUP BY id"
        )
            .map { results ->
                results.firstOrNull() ?: throw NotFoundException("No slice found with id $segmentId")
            }
    }

    override fun deleteById(podId: String, segmentId: String): Uni<Void> {
        return clickhouseClient.execute("ALTER TABLE ${databaseFromPodId(podId)}.$SLICE_TABLE DELETE WHERE id = '$segmentId'")
    }
}