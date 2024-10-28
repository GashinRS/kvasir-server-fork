package kvasir.plugins.kg.clickhouse.specs

import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import kvasir.definitions.kg.Pod
import kvasir.definitions.kg.RDFStatement
import kvasir.definitions.kg.Slice
import kvasir.plugins.kg.clickhouse.client.ClickhouseRecord
import kvasir.plugins.kg.clickhouse.client.InsertRecordSpec
import kvasir.plugins.kg.clickhouse.client.QuerySpec
import kvasir.utils.kg.KGProperty
import kvasir.utils.kg.KGPropertyKind
import kvasir.utils.kg.KGType
import kvasir.utils.kg.MetadataEntry

const val SYSTEM_DB = "_kvasir"
const val DATA_TABLE = "data"
const val META_DATA_TABLE = "metadata"
const val SLICE_TABLE = "slices"
const val POD_TABLE = "pods"
val DATA_COLUMNS = listOf("subject", "predicate", "object", "datatype", "language", "graph", "sign")
val META_DATA_COLUMNS = listOf("type_uri", "property_uri", "property_kind", "property_ref")
val SLICE_COLUMNS = listOf("id", "pod_id", "timestamp", "json")
val POD_COLUMNS = listOf("id", "timestamp", "json")

private fun statementToBaseRecord(t: RDFStatement): ClickhouseRecord {
    return ClickhouseRecord()
        .add(t.subject)
        .add(t.predicate)
        .add(t.`object`)
        .add(t.dataType ?: "")
        .add(t.language ?: "")
        .add(t.graph)
}

class RDFDatasetQuadInsertSpec(database: String) : InsertRecordSpec<RDFStatement>(database, DATA_TABLE, DATA_COLUMNS) {
    override fun toRecord(t: RDFStatement): ClickhouseRecord {
        return statementToBaseRecord(t)
            .add(1)
    }

}

class RDFDatasetQuadDeleteSpec(database: String) : InsertRecordSpec<RDFStatement>(database, DATA_TABLE, DATA_COLUMNS) {
    override fun toRecord(t: RDFStatement): ClickhouseRecord {
        return statementToBaseRecord(t)
            .add(-1)
    }
}

class MetadataInsertRecordSpec(database: String) :
    InsertRecordSpec<MetadataEntry>(database, META_DATA_TABLE, META_DATA_COLUMNS) {
    override fun toRecord(t: MetadataEntry): ClickhouseRecord {
        return ClickhouseRecord()
            .add(t.typeUri)
            .add(t.propertyUri)
            .add(t.propertyKind.name)
            .add(t.propertyRef)
    }
}

object SliceInsertRecordSpec : InsertRecordSpec<Slice>(SYSTEM_DB, SLICE_TABLE, SLICE_COLUMNS) {
    override fun toRecord(t: Slice): ClickhouseRecord {
        return ClickhouseRecord()
            .add(t.id)
            .add(t.podId)
            .add(System.currentTimeMillis())
            .add(t)
    }
}

object PodInsertRecordSpec : InsertRecordSpec<Pod>(SYSTEM_DB, POD_TABLE, POD_COLUMNS) {
    override fun toRecord(t: Pod): ClickhouseRecord {
        return ClickhouseRecord()
            .add(t.id)
            .add(System.currentTimeMillis())
            .add(t)
    }
}

class KGTypeQuerySpec(database: String) :
    QuerySpec<KGType, String>(database, META_DATA_TABLE, listOf("type_uri", "properties")) {
    override fun fromRecord(record: ClickhouseRecord): KGType {
        return KGType(
            uri = record.getString(0),
            properties = record.getJsonArray(1).map { it as JsonArray }.groupBy { it.getString(0) }
                .map { (property, records) ->
                    KGProperty(
                        uri = property,
                        kind = if (records.any { it.getString(1) == KGPropertyKind.IRI.name }) KGPropertyKind.IRI else KGPropertyKind.Literal,
                        typeRefs = records.map { it.getString(2) }.toSet()
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

class SliceQuerySpec(database: String) : QuerySpec<Slice, String>(database, SLICE_TABLE, SLICE_COLUMNS) {
    override fun fromRecord(record: ClickhouseRecord): Slice {
        return Json.decodeValue(record.getString(1), Slice::class.java)
    }
}

class PodQuerySpec : QuerySpec<Pod, String>(SYSTEM_DB, POD_TABLE, POD_COLUMNS) {
    override fun fromRecord(record: ClickhouseRecord): Pod {
        return Json.decodeValue(record.getString(1), Pod::class.java)
    }
}