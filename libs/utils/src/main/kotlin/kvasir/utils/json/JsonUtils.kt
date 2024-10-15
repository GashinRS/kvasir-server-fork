package kvasir.utils.json

import com.dashjoin.jsonata.Jsonata.jsonata
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.quarkus.jackson.ObjectMapperCustomizer
import jakarta.inject.Singleton

fun <T> Map<String, Any>.transform(jsonataExpr: String): T {
    return jsonata(jsonataExpr).evaluate(this) as T
}