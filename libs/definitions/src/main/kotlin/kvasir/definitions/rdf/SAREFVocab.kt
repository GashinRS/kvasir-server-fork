package kvasir.definitions.rdf

object SAREFVocab {

    const val baseUri = "https://saref.etsi.org/core/"

    const val Measurement = "${baseUri}Measurement"
    const val Sensor = "${baseUri}Sensor"

    const val hasTimestamp = "${baseUri}hasTimestamp"
    const val hasValue = "${baseUri}hasValue"
    const val measurementMadeBy = "${baseUri}measurementMadeBy"

}