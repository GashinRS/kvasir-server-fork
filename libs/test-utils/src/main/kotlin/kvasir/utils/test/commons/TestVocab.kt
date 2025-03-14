package kvasir.utils.test.commons

import java.time.Duration

object SchemaVocab {

    const val baseUri = "http://schema.org/"

    const val givenName = "${baseUri}givenName"
    const val familyName = "${baseUri}familyName"
    const val email = "${baseUri}email"

}

object ExampleVocab {

    const val baseUri = "http://example.org/"

    const val Person = "${baseUri}Person"
    const val knows = "${baseUri}knows"
    const val parent = "${baseUri}parent"

}