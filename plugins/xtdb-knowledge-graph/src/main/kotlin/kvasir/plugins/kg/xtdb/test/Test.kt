package kvasir.plugins.kg.xtdb.test

import com.google.common.hash.Hashing

fun main() {
    println(Hashing.farmHashFingerprint64().hashString("http://example.org/", Charsets.UTF_8))
    println(Hashing.farmHashFingerprint64().hashString("http://schema.org/", Charsets.UTF_8))
    println(Hashing.farmHashFingerprint64().hashString("http://kvasir.discover.ilabt.imec.be/vocab#", Charsets.UTF_8))
}