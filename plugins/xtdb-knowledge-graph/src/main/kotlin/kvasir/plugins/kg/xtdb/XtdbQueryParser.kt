package kvasir.plugins.kg.xtdb

import kvasir.definitions.kg.QueryRequest

class XtdbQueryParser(val queryRequest: QueryRequest) {

    fun toSQL(): String {
        val whereClauses = queryRequest.where.mapNotNull { mapWhere(it) }.takeIf { it.isNotEmpty() }
            ?.let { "WHERE ${it.joinToString(" AND ")}" } ?: ""
        return "SELECT s, p, o, t FROM ${queryRequest.podId} $whereClauses"
    }

    private fun mapWhere(conditionDoc: Map<String, Any>): String? {
        val filter = conditionDoc.entries.filterNot { it.key == "@id" }.joinToString(" AND ") { (key, value) ->
            if (value is String && value.startsWith("?")) {
                "p = '$key'"
            } else {
                "p = '$key' AND o = '$value'"
            }
        }
        return if (filter.isNotBlank()) {
            conditionDoc["@id"]?.let { id -> "s IN (SELECT s FROM ${queryRequest.podId} WHERE $filter)" }
                ?: filter
        } else {
            null
        }
    }

}