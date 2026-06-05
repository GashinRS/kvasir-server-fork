package kvasir.definitions.rdf

object KvasirVocab {

    const val baseUri = "https://kvasir.discover.ilabt.imec.be/vocab#"

    val context = mapOf(JsonLdKeywords.vocab to baseUri)

    const val AssertEmptyResult = "${baseUri}AssertEmptyResult"
    const val AssertNonEmptyResult = "${baseUri}AssertNonEmptyResult"
    const val AssertCountBounds = "${baseUri}AssertCountBounds"
    const val S3Reference = "${baseUri}S3Reference"

    const val autoIngestRDF = "${baseUri}autoIngestRDF"
    const val key = "${baseUri}key"
    const val versionId = "${baseUri}versionId"
    const val Pod = "${baseUri}Pod"
    const val EmbeddedSliceSchema = "${baseUri}EmbeddedSliceSchema"
    const val ExternalSliceSchema = "${baseUri}ExternalSliceSchema"
}

object KvasirNamedGraphs {

    const val baseUri = "https://kvasir.discover.ilabt.imec.be/named-graphs#"

    const val queryResultPaginationGraph = "${baseUri}qr-pagination"
    const val queryResultDataGraph = "${baseUri}qr-data"
    const val queryResultErrorsGraph = "${baseUri}qr-errors"

}

object FgaVocab {
    const val baseUri = "https://kvasir.discover.ilabt.imec.be/fine-grained-access#"

    const val Resource = "${baseUri}Resource"
    const val User = "${baseUri}User"

    const val allowed = "${baseUri}allowed"
    const val owner = "${baseUri}owner"
    const val parent = "${baseUri}parent"
    const val reader = "${baseUri}reader"
    const val writer = "${baseUri}writer"
    const val deleter = "${baseUri}deleter"
    const val blocked = "${baseUri}blocked"
    const val can_read = "${baseUri}can_read"
    const val can_write = "${baseUri}can_write"
    const val can_delete = "${baseUri}can_delete"
    const val can_manage = "${baseUri}can_manage"
    const val external_access = "${baseUri}external_access"

    val ALL_TYPES = setOf(
        Resource,
        User
    )

    val RESOURCE_RELATIONS = setOf(
        owner,
        parent,
        reader,
        writer,
        deleter,
        blocked,
        can_read,
        can_write,
        can_delete,
        can_manage
    )
}
