package kvasir.definitions.rdf

import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor

const val JSON_LD_MEDIA_TYPE = "application/ld+json"

object JsonLdKeywords {
    const val context = "@context"
    const val id = "@id"
    const val type = "@type"
    const val graph = "@graph"
    const val language = "@language"
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

    fun getFQName(prefixedName: String, context: Map<String, Any>, separator: String = ":"): String {
        return if (!prefixedName.contains(separator)) {
            prefixedName
        } else {
            val (prefix, localName) = prefixedName.split(separator, limit = 2)
            val ns = (context[prefix] ?: throw IllegalArgumentException("Unknown namespace prefix: $prefix")) as String
            "$ns$localName"
        }
    }

}