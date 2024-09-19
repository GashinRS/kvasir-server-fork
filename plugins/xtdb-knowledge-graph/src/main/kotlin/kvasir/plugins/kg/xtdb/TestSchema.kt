package kvasir.plugins.kg.xtdb

import graphql.schema.idl.SchemaParser

fun main() {
    val schema = """
    """.trimIndent()

    println(SchemaParser().parse(schema))
}