package kvasir.plugins.namespace.registry.prefix.cc

import io.quarkus.runtime.StartupEvent
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.ext.web.client.WebClient
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import kvasir.definitions.kg.NamespacePrefixRegistry
import kvasir.definitions.rdf.KvasirVocab

private const val CONTEXT_SOURCE = "https://prefix.cc/context"

@ApplicationScoped
class StaticNamespacePrefixRegistry(vertx: Vertx) : NamespacePrefixRegistry {

    private val webClient = WebClient.create(vertx)
    private lateinit var prefixes: Map<String, String>

    fun init(@Observes event: StartupEvent) {
        webClient.getAbs(CONTEXT_SOURCE)
            .send()
            .map { response ->
                prefixes = response.bodyAsJsonObject()
                    .getJsonObject("@context").associate { (key, value) -> key to value.toString() }
                    .plus("kvasir" to KvasirVocab.baseUri)
            }
            .await().indefinitely()
    }

    override fun getAll(): Map<String, String> = prefixes

    override fun get(prefix: String): String? = prefixes[prefix]

}