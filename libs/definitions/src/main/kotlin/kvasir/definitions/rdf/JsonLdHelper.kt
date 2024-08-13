package kvasir.definitions.rdf

import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor
import jakarta.ws.rs.BadRequestException

object JsonLdKeywords {
    const val context = "@context"
    const val id = "@id"
    const val type = "@type"
    const val graph = "@graph"
}

object JsonLdHelper {

    fun valueAsJsonArray(value: Any?): List<Map<String, Any>> {
        return when (value) {
            is List<*> -> value.map { it as Map<String, Any> }
            is Map<*, *> -> listOf(value as Map<String, Any>)
            else -> throw BadRequestException("Expected value to be a JSON array or object")
        }
    }

    // TODO: why do we need to supply the context here?
    fun toCompactFQForm(doc: Map<String, Any>, context: Map<String, Any>): Map<String, Any> {
        val docWithContext =
            if (doc.containsKey(JsonLdKeywords.context)) doc else doc + (JsonLdKeywords.context to context)
        return JsonLdProcessor.compact(JsonLdProcessor.expand(docWithContext), emptyMap<String, Any>(), JsonLdOptions())
    }

}