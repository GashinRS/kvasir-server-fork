package kvasir.definitions.kg.changes

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.rdf.KvasirVocab

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY
)
@JsonSubTypes(
    value = [JsonSubTypes.Type(value = S3Reference::class, name = KvasirVocab.S3Reference)]
)
interface Reference

@GenerateNoArgConstructor
data class S3Reference(
    val key: String,
    val versionId: String? = null
) : Reference