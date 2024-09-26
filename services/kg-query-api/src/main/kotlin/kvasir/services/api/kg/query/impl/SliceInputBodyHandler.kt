package kvasir.services.api.kg.query.impl

import com.github.jsonldjava.utils.JsonUtils
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.ext.MessageBodyReader
import jakarta.ws.rs.ext.Provider
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import kvasir.services.api.kg.query.SliceInput
import java.io.InputStream
import java.lang.reflect.Type

@Provider
class SliceInputBodyHandler : MessageBodyReader<SliceInput> {
    override fun isReadable(
        clazz: Class<*>,
        type: Type,
        annotations: Array<out Annotation>,
        mediaType: MediaType
    ): Boolean {
        return clazz == SliceInput::class.java
    }

    override fun readFrom(
        clazz: Class<SliceInput>,
        type: Type,
        annotations: Array<out Annotation>,
        mediaType: MediaType,
        headers: MultivaluedMap<String, String>,
        inputStream: InputStream
    ): SliceInput {
        val jsonLD = JsonUtils.fromInputStream(inputStream) as MutableMap<String, Any>
        val compactedFQJsonLD = JsonLdHelper.toCompactFQForm(jsonLD)
        return SliceInput(
            context = jsonLD[JsonLdKeywords.context] as Map<String, Any>,
            name = compactedFQJsonLD[KvasirVocab.name] as String,
            schema = compactedFQJsonLD[KvasirVocab.schema] as String,
            description = compactedFQJsonLD[KvasirVocab.description] as String? ?: "",
            targetGraphs = compactedFQJsonLD[KvasirVocab.targetGraphs] as Set<String>? ?: emptySet()
        )
    }
}