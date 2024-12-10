package kvasir.definitions.rdf

import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor

const val JSON_LD_MEDIA_TYPE = "application/ld+json"

object JsonLdKeywords {
    const val context = "@context"
    const val id = "@id"
    const val type = "@type"
    const val graph = "@graph"
    const val reverse = "@reverse"
    const val language = "@language"
    const val vocab = "@vocab"
}

object JsonLdHelper {

    fun toCompactFQForm(doc: Map<String, Any>, options: JsonLdOptions = JsonLdOptions()): Map<String, Any> {
        return JsonLdProcessor.compact(JsonLdProcessor.expand(doc), emptyMap<String, Any>(), options)
    }

    fun compactUri(uri: String, context: Map<String, Any>, separator: String = ":"): String {
        val compactedString = JsonLdProcessor.compact(mapOf(uri to uri), context, JsonLdOptions())
            .filter { it.key != "@context" }.keys.first()
        return if (compactedString == uri) {
            // Nothing to compact given the context
            uri
        } else {
            compactedString.split(":", limit = 2).takeIf { parts -> parts.size == 2 }?.let { (prefix, rest) ->
                "${prefix}${separator}${rest}"
            } ?: compactedString

        }
    }

    /**
     * Returns the fully qualified name of a prefixed name. Or null if the name is prefixed but the prefix is unknown.
     */
    fun getFQName(name: String, context: Map<String, Any>, separator: String = ":"): String? {
        return if (!name.contains(separator)) {
            name
        } else {
            val (prefix, localName) = name.split(separator, limit = 2)
            context[prefix]?.let { ns -> "$ns$localName" }
        }
    }
}