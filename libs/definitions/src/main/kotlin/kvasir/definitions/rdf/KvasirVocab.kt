package kvasir.definitions.rdf

object KvasirVocab {

    const val baseUri = "http://kvasir.discover.ilabt.imec.be/vocab#"

    const val ChangeRequest = "${baseUri}ChangeRequest"
    const val QueryRequest = "${baseUri}QueryRequest"
    const val QueryResult = "${baseUri}QueryResult"
    const val AssertEmptyResult = "${baseUri}AssertEmptyResult"
    const val AssertNonEmptyResult = "${baseUri}AssertNonEmptyResult"
    const val InsertTemplate = "${baseUri}InsertTemplate"
    const val DeleteThenInsertTemplate = "${baseUri}DeleteThenInsertTemplate"
    const val DeleteTemplate = "${baseUri}DeleteTemplate"
    const val graph = "${baseUri}graph"
    const val inserts = "${baseUri}inserts"
    const val deletes = "${baseUri}deletes"
    const val select = "${baseUri}select"
    const val assertions = "${baseUri}assertions"
    const val operations = "${baseUri}operations"
    const val results = "${baseUri}results"
    const val nextCursor = "${baseUri}nextCursor"
    const val where = "${baseUri}where"
    const val query = "${baseUri}query"
    const val transform = "${baseUri}transform"
    const val outputMediaType = "${baseUri}outputMediaType"
}