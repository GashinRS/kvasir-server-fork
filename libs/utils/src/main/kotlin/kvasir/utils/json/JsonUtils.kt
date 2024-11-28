package kvasir.utils.json

import com.dashjoin.jsonata.Jsonata.jsonata

fun <T> Map<String, Any>.transform(jsonataExpr: String): T {
    return jsonata(jsonataExpr).evaluate(this) as T
}