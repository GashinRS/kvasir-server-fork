package kvasir.definitions.reactive

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import java.util.concurrent.CompletableFuture

// TODO: better name, this is a bit confusing as the function does not "block" or waits, but rather continues when the last emitted value in the Multi is received.
fun Multi<Void>.skipToLast(): Uni<Void> {
    return this.skip().where { true }.toUni()
}

fun <T> CompletableFuture<T>.toUni(): Uni<T> {
    return Uni.createFrom().completionStage(this)
}