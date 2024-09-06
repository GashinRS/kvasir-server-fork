package kvasir.plugins.kg.xtdb

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import io.vertx.core.json.JsonObject
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab

internal val testContext = mapOf(
    "kss" to KvasirVocab.baseUri,
    "so" to "http://schema.org/",
    "ex" to "http://example.org/"
)

internal val RESOURCE_ALICE_BASIC = Person(
    id = "ex:alice",
    givenName = "Alice",
    familyName = "Smith"
)

internal val QUERY_ALICE_BASIC = """
    query {
        ex_Person(id: "ex:alice") {
            so_givenName
            so_familyName
        }
    }
""".trimIndent()

interface NamedResource {
    @get:JsonProperty("@id")
    val id: String

    @get:JsonProperty("@type")
    val type: String

    @get:JsonProperty("so:givenName")
    val givenName: String
}

interface Pet : NamedResource {
    @get:JsonProperty("ex:owner")
    val owner: NamedResource?
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Person(
    override val id: String,
    override val givenName: String,
    @get:JsonProperty("so:familyName")
    val familyName: String,
    @get:JsonProperty("ex:knows")
    val knows: Set<Person>? = null,
    override val type: String = "ex:Person"
) : NamedResource

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Cat(
    override val id: String,
    override val givenName: String,
    override val owner: NamedResource?,
    override val type: String = "ex:Cat"
) : Pet

object NamedResourceUtils {

    fun toJSONLD(resource: NamedResource): List<Map<String, Any>> {
        val doc = JsonObject.mapFrom(resource).map.plus("@context" to testContext)
        return JsonLdHelper.toCompactFQForm(doc).let {
            if (it.containsKey(JsonLdKeywords.graph)) {
                it[JsonLdKeywords.graph] as List<Map<String, Any>>
            } else {
                listOf(it)
            }
        }
    }

}