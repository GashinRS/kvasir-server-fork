package kvasir.plugins.kg.xtdb

import io.vertx.core.json.Json
import kvasir.definitions.kg.QueryRequest
import org.junit.jupiter.api.Test

class XtdbQueryParserTest {

    @Test
    fun testQuery1() {
        val json = """
            {
              "podId": "docs",
              "where": [{
                "@id": "?s",
                "@type": "schema:Person",
                "schema:givenName": "?name"
              }],
              "select": ["?name"]
            }
        """.trimIndent()
        val qr = Json.decodeValue(json, QueryRequest::class.java)
        println(XtdbQueryParser(qr).toSQL())
    }

    @Test
    fun testQuery2() {
        val json = """
            {
              "podId": "docs",
              "where": [{
                "@id": "?s",
                "@type": "schema:Person",
                "schema:givenName": "?name"
              }],
              "select": [ { "?s" : [ "schema:givenName", { "ex:friends" : ["*"] } ] } ]
            }
        """.trimIndent()
        val qr = Json.decodeValue(json, QueryRequest::class.java)
        println(XtdbQueryParser(qr).toSQL())
    }

}