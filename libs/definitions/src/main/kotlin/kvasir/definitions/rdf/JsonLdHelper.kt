package kvasir.definitions.rdf

import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor

const val JSON_LD_MEDIA_TYPE = "application/ld+json"

object JsonLdKeywords {
    const val context = "@context"
    const val id = "@id"
    const val type = "@type"
    const val graph = "@graph"
}

object JsonLdHelper {

    fun toCompactFQForm(doc: Map<String, Any>): Map<String, Any> {
        return JsonLdProcessor.compact(JsonLdProcessor.expand(doc), emptyMap<String, Any>(), JsonLdOptions())
    }

    fun compactUri(uri: String, context: Map<String, Any>, separator: String = ":"): String {
        val (prefix, rest) = JsonLdProcessor.compact(mapOf(uri to uri), context, JsonLdOptions())
            .filter { it.key != "@context" }.keys.first().split(":")
        return "${prefix}${separator}${rest}"
    }

}