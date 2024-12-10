package kvasir.definitions.rdf

import kvasir.definitions.rdf.KvasirVocab.baseUri

object KvasirVocab {

    const val baseUri = "https://kvasir.discover.ilabt.imec.be/vocab#"

    val context = mapOf(JsonLdKeywords.vocab to baseUri)

    const val AssertEmptyResult = "${baseUri}AssertEmptyResult"
    const val AssertNonEmptyResult = "${baseUri}AssertNonEmptyResult"
    const val S3Reference = "${baseUri}S3Reference"

    const val autoIngestRDF = "${baseUri}autoIngestRDF"
    const val assert = "${baseUri}assert"
    const val configuration = "${baseUri}configuration"
    const val defaultContext = "${baseUri}defaultContext"
    const val delete = "${baseUri}delete"
    const val description = "${baseUri}description"
    const val errorMessage = "${baseUri}errorMessage"
    const val graph = "${baseUri}graph"
    const val insert = "${baseUri}insert"
    const val key = "${baseUri}key"
    const val name = "${baseUri}name"
    const val nrOfDeletes = "${baseUri}nrOfDeletes"
    const val nrOfInserts = "${baseUri}nrOfInserts"
    const val podId = "${baseUri}podId"
    const val query = "${baseUri}query"
    const val resultCode = "${baseUri}resultCode"
    const val schema = "${baseUri}schema"
    const val shacl = "${baseUri}shacl"
    const val sliceId = "${baseUri}sliceId"
    const val totalCount = "${baseUri}totalCount"
    const val targetGraphs = "${baseUri}targetGraphs"
    const val timestamp = "${baseUri}timestamp"
    const val versionId = "${baseUri}versionId"
    const val with = "${baseUri}with"
}

object KvasirNamedGraphs {

    const val baseUri = "https://kvasir.discover.ilabt.imec.be/named-graphs#"

    const val queryResultPaginationGraph = "${baseUri}qr-pagination"
    const val queryResultDataGraph = "${baseUri}qr-data"
    const val queryResultErrorsGraph = "${baseUri}qr-errors"

}