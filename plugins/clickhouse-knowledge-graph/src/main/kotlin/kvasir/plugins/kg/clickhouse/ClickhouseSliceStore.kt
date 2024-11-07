package kvasir.plugins.kg.clickhouse

import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.ChangeResultSliceFilter
import kvasir.definitions.kg.Slice
import kvasir.definitions.kg.SliceStore
import kvasir.definitions.kg.SliceSummary
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.specs.SLICE_TABLE
import kvasir.plugins.kg.clickhouse.specs.SliceInsertRecordSpec
import kvasir.plugins.kg.clickhouse.specs.SliceQuerySpec
import kvasir.utils.shacl.RDF4JSHACLValidator

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
        return clickhouseClient.execute("ALTER TABLE ${databaseFromPodId(podId)}.$SLICE_TABLE DELETE WHERE id = '$segmentId' AND pod_id = '$podId'")
    }

    override fun loadFilterById(
        podId: String,
        segmentId: String
    ): Uni<ChangeResultSliceFilter?> {
        return getById(podId, segmentId)
            .map { slice -> slice?.let { sliceFilterFrom(it) } }
    }

    override fun loadAllFilters(podId: String): Uni<Set<ChangeResultSliceFilter>> {
        return clickhouseClient.query(
            SliceQuerySpec(),
            "SELECT id, argMax(json, timestamp) FROM $SLICE_TABLE GROUP BY id"
        )
            .map { results -> results.map { sliceFilterFrom(it) }.toSet() }
    }

    private fun sliceFilterFrom(slice: Slice): ChangeResultSliceFilter {
        return object : ChangeResultSliceFilter {

            val validator = RDF4JSHACLValidator.fromTurtleString(slice.shacl)

            override fun podId(): String {
                return slice.podId
            }

            override fun sliceId(): String {
                return slice.id
            }

            override fun test(instance: List<Map<String, Any>>): Boolean {
                return instance.all { validator.filter(it) }
            }

        }
    }
}