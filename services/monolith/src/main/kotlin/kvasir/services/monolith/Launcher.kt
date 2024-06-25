package kvasir.services.monolith

import io.quarkus.runtime.Quarkus

fun main(args: Array<String>) {
    println("Starting the Kvasir monolith...")
    Quarkus.run(*args)
}