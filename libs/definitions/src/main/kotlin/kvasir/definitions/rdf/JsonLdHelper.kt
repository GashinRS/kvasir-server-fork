package kvasir.definitions.rdf

import jakarta.ws.rs.BadRequestException

object JsonLdHelper {

    fun valueAsJsonArray(value: Any?): List<Map<String, Any>> {
        return when (value) {
            is List<*> -> value.map { it as Map<String, Any> }
            is Map<*, *> -> listOf(value as Map<String, Any>)
            else -> throw BadRequestException("Expected value to be a JSON array or object")
        }
    }

}