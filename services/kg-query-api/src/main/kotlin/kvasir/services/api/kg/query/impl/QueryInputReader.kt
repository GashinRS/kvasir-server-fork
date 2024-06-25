package kvasir.services.api.kg.query.impl

import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor
import com.github.jsonldjava.utils.JsonUtils
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.ext.MessageBodyReader
import jakarta.ws.rs.ext.Provider
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.KvasirVocab
import kvasir.services.api.kg.query.QueryInput
import java.io.InputStream
import java.lang.reflect.Type

@Provider
class QueryInputReader(
    private val uriInfo: UriInfo
) : MessageBodyReader<QueryInput> {

    private val defaultContext = mapOf(
        "select" to KvasirVocab.select,
        "where" to KvasirVocab.where
    )

    override fun isReadable(
        clazz: Class<*>,
        type: Type,
        annotations: Array<out Annotation>,
        mediaType: MediaType
    ): Boolean {
        return clazz == QueryInput::class.java
    }

    override fun readFrom(
        clazz: Class<QueryInput>,
        type: Type,
        annotations: Array<out Annotation>,
        mediatType: MediaType,
        headers: MultivaluedMap<String, String>,
        inputStream: InputStream
    ): QueryInput {
        val jsonLD = JsonUtils.fromInputStream(inputStream) as MutableMap<String, Any>
        if (!jsonLD.containsKey("@context")) {
            jsonLD["@context"] =
                defaultContext.plus("@vocab" to uriInfo.requestUri.toASCIIString().removeSuffix("inbox"))
        }
        val resolvedJsonLD = JsonLdProcessor.compact(
            JsonLdProcessor.expand(jsonLD),
            JsonUtils.fromString("{}"),
            JsonLdOptions()
        )
        return QueryInput(
            providedContext = jsonLD["@context"] as Map<String, Any>,
            where = resolvedJsonLD[KvasirVocab.where]?.let { where -> JsonLdHelper.valueAsJsonArray(where) }
                ?: emptyList(),
            select = resolvedJsonLD[KvasirVocab.select]?.let { select -> JsonLdHelper.valueAsJsonArray(select) }
                ?: emptyList()
        )
    }
}