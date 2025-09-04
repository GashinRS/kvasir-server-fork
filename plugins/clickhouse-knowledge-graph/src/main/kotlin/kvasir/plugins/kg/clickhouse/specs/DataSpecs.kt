package kvasir.plugins.kg.clickhouse.specs

import io.vertx.core.json.JsonArray
import kvasir.definitions.kg.*
import kvasir.definitions.kg.timeseries.Observation
import kvasir.definitions.rdf.XSDVocab
import kvasir.plugins.kg.clickhouse.client.ClickhouseRecord
import kvasir.plugins.kg.clickhouse.client.InsertRecordSpec
import kvasir.plugins.kg.clickhouse.client.QuerySpec
import java.time.temporal.ChronoUnit

const val SYSTEM_DB = "_kvasir"
const val DATA_TABLE = "data"
const val META_DATA_TABLE = "metadata"
const val TIME_SERIES_DATA_TABLE = "observations"
const val TIME_SERIES_TABLE = "series"
val DATA_COLUMNS =
    listOf("subject", "predicate", "object", "datatype", "language", "graph", "timestamp", "change_request_id", "sign")
val META_DATA_COLUMNS = listOf("type_uri", "property_uri", "property_kind", "property_ref")
val SORT_COLUMNS = listOf("subject", "predicate", "object", "datatype", "language", "graph")
val REVERSED_SORT_COLUMNS = listOf("object", "predicate", "subject", "datatype", "language", "graph")

val TIME_SERIES_DATA_COLUMNS =
    listOf(
        "id",
        "graph",
        "series_id",
        "change_request_id",
        "change_request_ts",
        "timestamp",
        "value_number",
        "value_string",
        "value_bool",
        "value_datatype",
        "value_lang",
        "labels"
    )

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
        .add(record.changeRequestId)
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

class KGTypeQuerySpec(database: String) :
    QuerySpec<KGType, String>(database, META_DATA_TABLE, listOf("type_uri", "properties")) {
    override fun fromRecord(record: ClickhouseRecord): KGType {
        return KGType(
            uri = record.getString(0),
            properties = record.getJsonArray(1).map { it as JsonArray }
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

class ObservationInsertRecordSpec(database: String) :
    InsertRecordSpec<Observation>(database, TIME_SERIES_DATA_TABLE, TIME_SERIES_DATA_COLUMNS) {
    override fun toRecord(t: Observation): ClickhouseRecord {
        val storeAsNumber = t.dataType?.let {
            it in setOf(
                XSDVocab.int,
                XSDVocab.long,
                XSDVocab.double,
                XSDVocab.float,
                XSDVocab.decimal,
                XSDVocab.integer
            )
        } ?: false
        val storeAsBoolean = t.dataType?.let { it == XSDVocab.boolean } ?: false
        return ClickhouseRecord()
            .add(t.id)
            .add(t.graph)
            .add(t.series)
            .add(t.changeRequestId)
            .add(t.changeRequestTimestamp.truncatedTo(ChronoUnit.MILLIS))
            .add(t.timestamp.truncatedTo(ChronoUnit.MICROS))
            .add(if (storeAsNumber) t.value else Double.NaN)
            .add(if (!storeAsNumber && !storeAsBoolean) t.value else "")
            .add(if (storeAsBoolean) t.value else false)
            .add(t.dataType ?: "")
            .add(t.language ?: "")
            .add(t.labels)
    }
}