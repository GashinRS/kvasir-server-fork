package kvasir.definitions.kg.changeops

import com.fasterxml.jackson.annotation.JsonProperty
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab

class InvalidTemplateException(message: String) : RuntimeException(message)