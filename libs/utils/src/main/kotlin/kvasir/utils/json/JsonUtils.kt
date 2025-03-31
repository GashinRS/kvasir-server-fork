package kvasir.utils.json

import com.dashjoin.jsonata.Jsonata.jsonata
import io.vertx.core.json.JsonObject

fun <T> Map<String, Any>.transform(jsonataExpr: String): T {
    return jsonata(jsonataExpr).evaluate(this) as T
}

fun convertToJsonMap(obj: Any): Map<String, Any> {
    return when (obj) {
        is Map<*, *> -> obj as Map<String, Any>
        is JsonObject -> obj.map
        else -> throw IllegalArgumentException("Cannot convert '$obj' to Map<String, Any>")
    }
}