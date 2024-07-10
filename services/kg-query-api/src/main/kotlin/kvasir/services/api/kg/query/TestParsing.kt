package kvasir.services.api.kg.query

import graphql.language.AstTransformer
import graphql.language.Document
import graphql.parser.Parser
import kvasir.definitions.kg.QueryRequest

fun main() {
    val context = mapOf(
        "name" to "http://schema.org/givenName",
        "email" to "http://schema.org/email"
    )
    val input = QueryInput(
        query = """
            {
              name
              email
            }
        """.trimIndent()
    )
    val queryDoc = Parser.parse(input.query)
    val contextualizedDoc = AstTransformer().transform(queryDoc, ContextualizingQueryVisitor(context))
    println(
        QueryRequest(
            "docs",
            contextualizedDoc as Document,
            input.variables,
            input.operationName
        )
    )
}