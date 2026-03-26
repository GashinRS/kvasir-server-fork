package kvasir.plugins.kg.clickhouse.specs

import io.vertx.core.json.JsonArray
import kvasir.definitions.kg.*
import kvasir.plugins.kg.clickhouse.client.ClickhouseRecord
import kvasir.plugins.kg.clickhouse.client.InsertRecordSpec
import kvasir.plugins.kg.clickhouse.client.QuerySpec

const val SYSTEM_DB = "_kvasir"
const val DATA_TABLE = "data"
const val COLLAPSED_STATE_BY_TYPE_TABLE = "collapsed_state_by_type"
const val META_DATA_TABLE = "metadata"
val DATA_COLUMNS =
    listOf("subject", "predicate", "object", "datatype", "language", "graph", "timestamp", "change_id", "sign")
val SORT_COLUMNS = listOf("subject", "predicate", "object", "datatype", "language", "graph")
val REVERSED_SORT_COLUMNS = listOf("object", "predicate", "subject", "datatype", "language", "graph")

private fun statementToBaseRecord(record: ChangeRecord): ClickhouseRecord {
    val t = record.statement
    return ClickhouseRecord()
        .add(t.subject)
        .add(t.predicate)
        .add(t.`object`)
        .add(t.dataType ?: "")
        .add(t.language ?: "")
        .add(t.graph)
        .add(record.timestamp.toEpochMilli())
        .add(record.changeId)
        .add(
            when (record.type) {
                ChangeRecordType.INSERT -> 1
                ChangeRecordType.DELETE -> -1
            }
        )
}

class RDFDatasetQuadInsertSpec(database: String) : InsertRecordSpec<ChangeRecord>(database, DATA_TABLE, DATA_COLUMNS) {
    override fun toRecord(t: ChangeRecord): ClickhouseRecord {
        return statementToBaseRecord(t)
    }

}

class KGTypeQuerySpec(database: String) :
    QuerySpec<KGType, String>(database, META_DATA_TABLE, listOf("type_uri", "properties")) {
    override fun fromRecord(record: ClickhouseRecord): KGType {
        return KGType(
            uri = record.getString(0),
            properties = record.getJsonArray(1).map { it as JsonArray }
                .filterNot { it.getString(0).isBlank() }
                .groupBy { it.getString(0) }
                .map { (property, records) ->
                    KGProperty(
                        uri = property,
                        typeRefs = records.map { record ->
                            val kind = KGPropertyKind.valueOf(record.getString(1))
                            val typeName = record.getString(2)
                            KGTypeReference(kind, typeName)
                        }.toSet()
                    )
                }
        )
    }
}

class GenericQuerySpec(database: String = SYSTEM_DB, table: String, private val columns: List<String>) :
    QuerySpec<Map<String, Any>, String>(database, table, columns) {
    override fun fromRecord(record: ClickhouseRecord): Map<String, Any> {
        return columns.mapIndexed { index, column ->
            column to record.getValue(index)
        }.toMap()
    }

}