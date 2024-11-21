package kvasir.utils.string

import java.util.*


fun String.encodeB64(): String {
    return Base64.getEncoder().encodeToString(this.toByteArray())
}

fun String.decodeB64(): String {
    return String(Base64.getDecoder().decode(this))
}