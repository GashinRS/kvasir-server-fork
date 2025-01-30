package kvasir.definitions.rdf

object XSDVocab {

    const val baseUri = "http://www.w3.org/2001/XMLSchema#"

    val string = "${baseUri}string"
    val boolean = "${baseUri}boolean"
    val int = "${baseUri}int"
    val integer = "${baseUri}integer"
    val long = "${baseUri}long"
    val double = "${baseUri}double"
    val float = "${baseUri}float"
    val decimal = "${baseUri}decimal"
    val dateTime = "${baseUri}dateTime"

    val literalTypes = setOf(string, boolean, int, integer, long, double, decimal, dateTime)
}
