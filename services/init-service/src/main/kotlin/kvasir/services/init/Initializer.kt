package kvasir.services.init

import io.quarkus.logging.Log
import io.quarkus.runtime.StartupEvent
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import kvasir.definitions.config.BootstrapConfig
import kvasir.definitions.config.KvasirConfig
import kvasir.definitions.reactive.skipToLast
import kvasir.plugins.kg.clickhouse.ClickhouseInitializer
import kvasir.utils.pod.PodSetupHelper
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.concurrent.atomic.AtomicBoolean

@ApplicationScoped
class Initializer(
    @ConfigProperty(name = KvasirConfig.BASE_URI_PROPERTY, defaultValue = KvasirConfig.BASE_URI_DEFAULT)
    private val baseUri: String,
    private val dbInitializer: ClickhouseInitializer
) : PodSetupHelper() {

    private val initializationComplete = AtomicBoolean(false)

    fun init(
        @Observes event: StartupEvent,
        config: BootstrapConfig,
    ) {
        // Init system db
        dbInitializer.init()
            // Init auth (global)
            .chain { _ ->
                if (podAuthInitializer.isResolvable) {
                    Log.debug("Initializing global auth configuration")
                    podAuthInitializer.get().initialize()
                } else {
                    Log.debug("No global auth initializer available, skipping global auth setup")
                    Uni.createFrom().voidItem()
                }
            }
            .chain { _ ->
                // Init pods based on config
                Multi.createFrom().iterable(config.pods())
                    .onItem().transformToUni { podConfig ->
                        val podId = "${baseUri}${podConfig.name()}"
                        createPod(podId, podConfig)
                    }
                    .concatenate()
                    .onCompletion().invoke { initializationComplete.set(true) }
                    .skipToLast()
            }.await().indefinitely()
    }

    fun isInitialized(): Boolean = initializationComplete.get()
}
