package kvasir.plugins.kg.clickhouse.utils

import kvasir.utils.rdf.RDFLiteralUtils

object RDFUtils {

    fun parseLabelValue(labelValue: String): Any {
        return if (labelValue.startsWith("\"")) {
            val value = labelValue.removePrefix("\"").substringBeforeLast("\"")
            val typeInfo = labelValue.substringAfterLast("\"")
            if (typeInfo.isEmpty() || typeInfo.startsWith("@")) {
                value // Return value as string
            } else {
                // Parse datatype
                val dataType = typeInfo.substringAfter("^^<").substringBeforeLast(">")
                RDFLiteralUtils.getCompatibleRawValue(value, dataType)
            }
        } else {
            // The label value is an IRI: wrap in an object with id attribute
            mapOf("id" to labelValue)
        }
    }

}