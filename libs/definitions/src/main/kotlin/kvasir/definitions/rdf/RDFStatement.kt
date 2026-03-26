package kvasir.definitions.rdf

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_DEFAULT)
data class RDFStatement(
    val subject: String,
    val predicate: String,
    val `object`: String,
    val graph: String = "",
    val dataType: String? = null,
    val language: String? = null
)