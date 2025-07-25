package kvasir.definitions.rdf

object KvasirVocab {

    const val baseUri = "https://kvasir.discover.ilabt.imec.be/vocab#"

    val context = mapOf(JsonLdKeywords.vocab to baseUri)

    const val AssertEmptyResult = "${baseUri}AssertEmptyResult"
    const val AssertNonEmptyResult = "${baseUri}AssertNonEmptyResult"
    const val S3Reference = "${baseUri}S3Reference"

    const val autoIngestRDF = "${baseUri}autoIngestRDF"
    const val ownerUserId = "${baseUri}ownerUserId"
    const val assert = "${baseUri}assert"
    const val configuration = "${baseUri}configuration"
    const val defaultContext = "${baseUri}defaultContext"
    const val delete = "${baseUri}delete"
    const val description = "${baseUri}description"
    const val message = "${baseUri}message"
    const val graph = "${baseUri}graph"
    const val insert = "${baseUri}insert"
    const val key = "${baseUri}key"
    const val name = "${baseUri}name"
    const val nrOfDeletes = "${baseUri}nrOfDeletes"
    const val nrOfInserts = "${baseUri}nrOfInserts"
    const val podId = "${baseUri}podId"
    const val query = "${baseUri}query"
    const val statusCode = "${baseUri}statusCode"
    const val statusEntry = "${baseUri}statusEntry"
    const val schema = "${baseUri}schema"
    const val shacl = "${baseUri}shacl"
    const val type = "${baseUri}type"
    const val sliceId = "${baseUri}sliceId"
    const val totalCount = "${baseUri}totalCount"
    const val targetGraphs = "${baseUri}targetGraphs"
    const val timestamp = "${baseUri}timestamp"
    const val versionId = "${baseUri}versionId"
    const val with = "${baseUri}with"
    const val authConfiguration = "${baseUri}authConfiguration"
    const val serverUrl = "${baseUri}serverUrl"
    const val clientId = "${baseUri}clientId"
    const val clientSecret = "${baseUri}clientSecret"
    const val profile = "${baseUri}profile"
    const val authServerUrl = "${baseUri}authServerUrl"
    const val supportsChanges = "${baseUri}supportChanges"
    const val variables = "${baseUri}variables"
    const val operationName = "${baseUri}operationName"
    const val atTimestamp = "${baseUri}atTimestamp"
    const val atChangeRequestId = "${baseUri}atChangeRequestId"
    const val objectId = "${baseUri}objectId"
    const val externalObjectUri = "${baseUri}externalObjectUri"
    const val internalObjectUri = "${baseUri}internalObjectUri"
    const val mutationType = "${baseUri}mutationType"
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
    const val Group = "${baseUri}Group"
    const val User = "${baseUri}User"

    const val allowed = "${baseUri}allowed"
    const val member = "${baseUri}member"
    const val owner = "${baseUri}owner"
    const val parent = "${baseUri}parent"
    const val reader = "${baseUri}reader"
    const val writer = "${baseUri}writer"
    const val deleter = "${baseUri}deleter"
    const val manager = "${baseUri}manager"
    const val blocked = "${baseUri}blocked"
    const val can_read = "${baseUri}can_read"
    const val can_write = "${baseUri}can_write"
    const val can_delete = "${baseUri}can_delete"
    const val can_manage = "${baseUri}can_manage"

    val ALL_TYPES = setOf(
        Resource,
        Group,
        User
    )

    val GROUP_RELATIONS = setOf(member)
    val RESOURCE_RELATIONS = setOf(
        owner,
        parent,
        reader,
        writer,
        deleter,
        manager,
        blocked,
        can_read,
        can_write,
        can_delete,
        can_manage
    )
}