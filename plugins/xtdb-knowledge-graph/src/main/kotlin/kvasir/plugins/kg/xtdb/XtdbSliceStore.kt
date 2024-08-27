package kvasir.plugins.kg.xtdb

import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.NotFoundException
import kvasir.definitions.graphql.GraphQLUtils
import kvasir.definitions.kg.Slice
import kvasir.definitions.kg.SliceSummary
import kvasir.definitions.kg.SliceStore

@ApplicationScoped
class XtdbSliceStore(
    private val xtdbClient: XtdbClient,
) : SliceStore {

    companion object {
        private const val segmentDatabase = "kvasir_segments"
    }

    override fun persist(segment: Slice): Uni<Void> {
        return xtdbClient.execute(
            SqlTransaction(
                SqlOp(
                    "ASSERT NOT EXISTS (SELECT 1 FROM users WHERE _id = '${segment.id}'); INSERT INTO $segmentDatabase (_id, context, podId, name, description, spec, targetGraphs) VALUES (?, ?, ?, ?, ?, ?);",
                    listOf(
                        listOf(
                            segment.id,
                            segment.context,
                            segment.podId,
                            segment.name,
                            segment.description,
                            segment.spec,
                            segment.targetGraphs
                        )
                    )
                )
            )
        )
    }

    override fun list(podId: String): Uni<List<SliceSummary>> {
        val sql = "SELECT _id, name, description FROM $segmentDatabase WHERE podId = '$podId';"
        Log.debug("Xtdb query: $sql")
        return xtdbClient.query(SqlQuery(sql)).map { results ->
            results.map { result ->
                SliceSummary(
                    id = result["_id"] as String,
                    name = result["name"] as String,
                    description = result["description"] as String
                )
            }
        }
    }

    override fun getById(segmentId: String): Uni<Slice> {
        val sql = "SELECT * FROM $segmentDatabase WHERE _id = '$segmentId';"
        Log.debug("Xtdb query: $sql")
        return xtdbClient.query(SqlQuery(sql)).chain { results ->
            results.firstOrNull()?.let { result ->
                Uni.createFrom().item(
                    Slice(
                        id = result["_id"] as String,
                        context = result["context"] as Map<String, Any>,
                        podId = result["podId"] as String,
                        name = result["name"] as String,
                        description = result["description"] as String,
                        spec = result["spec"] as String,
                        targetGraphs = result["targetGraphs"] as Set<String>
                    )
                )
            } ?: Uni.createFrom().failure(NotFoundException("Segment not found: $segmentId"))
        }
    }

    override fun deleteById(segmentId: String): Uni<Void> {
        return xtdbClient.execute(
            SqlTransaction(
                SqlOp(
                    "DELETE FROM $segmentDatabase WHERE _id = ?;",
                    listOf(listOf(segmentId))
                )
            )
        )
    }
}