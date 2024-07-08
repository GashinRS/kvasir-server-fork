package kvasir.plugins.kg.xtdb

import com.github.jsonldjava.core.JsonLdProcessor
import graphql.parser.Parser
import io.vertx.core.json.Json

fun main() {
    val regex = Regex("`.*`", setOf(RegexOption.MULTILINE))
    val context = mapOf(
        "ex" to "http://example.org/"
    )

//    val q = """
//        query {
//          ex_person @single {
//            `ex:name` @single(_: "Alice") {
//              `ex:givenName`
//            }
//            `ex:friends` {
//              `ex:name`
//            }
//          }
//        }
//    """.trimIndent()
    val q = """
        {
          uuid
          friend {
            name
            age
          }
          ... on Person {
            name
            email
          }
        }
    """.trimIndent()
    val safeQ = regex.replace(q) { result ->
        val (namespace, propName) = result.value.removeSurrounding("`").split(":")
        val fqName = getFQUri("$namespace:$propName", context)
        propName.plus(" @context(iri:\"$fqName\")")
    }
    println(safeQ)
    val doc = Parser.parse(safeQ)
    println(doc)
}

private fun getFQUri(namespacedStr: String, context: Map<String, String>): String {
    val doc =mapOf("@context" to context, namespacedStr to true)
    println(Json.encode(doc))
    val expandedForm =
        JsonLdProcessor.expand(doc).first() as Map<String, List<Any>>
    return expandedForm.keys.first()
}