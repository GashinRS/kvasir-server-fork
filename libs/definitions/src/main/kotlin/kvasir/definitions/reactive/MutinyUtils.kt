package kvasir.definitions.reactive

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.vertx.core.Future
import mutiny.zero.flow.adapters.AdaptersToFlow
import org.reactivestreams.Publisher
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

fun Multi<Void>.skipToLast(): Uni<Void> {
    return this.skip().where { true }.toUni()
}

fun <T> CompletableFuture<T>.toUni(): Uni<T> {
    return Uni.createFrom().completionStage(this)
}

fun <T> CompletionStage<T>.toUni(): Uni<T> {
    return Uni.createFrom().completionStage(this)
}

fun <T> Iterable<T>.asMulti(): Multi<T> {
    return Multi.createFrom().iterable(this)
}

fun <T> Publisher<T>.toMulti(): Multi<T> {
    return Multi.createFrom().publisher(AdaptersToFlow.publisher(this))
}

fun <T> Future<T>.toUni(): Uni<T> {
    return Uni.createFrom().completionStage { this.toCompletionStage() }
}