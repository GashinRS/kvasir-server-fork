package kvasir.definitions.rdf

object KvasirVocab {

    const val baseUri = "http://kvasir.discover.ilabt.imec.be/vocab#"

    const val ChangeRequest = "${baseUri}ChangeRequest"
    const val QueryRequest = "${baseUri}QueryRequest"
    const val QueryResult = "${baseUri}QueryResult"
    const val inserts = "${baseUri}inserts"
    const val deletes = "${baseUri}deletes"
    const val select = "${baseUri}select"
    const val where = "${baseUri}where"
    const val results = "${baseUri}results"
    const val nextCursor = "${baseUri}nextCursor"
}