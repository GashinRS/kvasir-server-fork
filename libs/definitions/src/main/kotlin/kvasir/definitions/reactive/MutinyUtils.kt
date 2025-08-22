package kvasir.definitions.reactive

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import kvasir.definitions.kg.ChangeStatusCode
import java.time.Duration
import java.util.concurrent.CompletableFuture

fun Multi<Void>.skipToLast(): Uni<Void> {
    return this.skip().where { true }.toUni()
}

fun <T> CompletableFuture<T>.toUni(): Uni<T> {
    return Uni.createFrom().completionStage(this)
}

fun <T> Iterable<T>.asMulti(): Multi<T> {
    return Multi.createFrom().iterable(this)
}