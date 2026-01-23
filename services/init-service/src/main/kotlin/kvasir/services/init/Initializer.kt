package kvasir.services.init

import io.quarkus.logging.Log
import io.quarkus.runtime.Quarkus
import io.quarkus.runtime.StartupEvent
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.vertx.core.json.Json
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Instance
import kvasir.definitions.annotations.StorageLevel
import kvasir.definitions.auth.AuthInitializer
import kvasir.definitions.config.BootstrapConfig
import kvasir.definitions.config.HttpConfig
import kvasir.definitions.persistence.StorageLifecycleManager
import kvasir.definitions.reactive.skipToLast
import kvasir.utils.persistence.PersistentEntityDetector
import kvasir.utils.pod.PodSetupHelper
import java.util.concurrent.atomic.AtomicBoolean

@ApplicationScoped
class Initializer(
    private val podSetupHelper: PodSetupHelper,
    private val podAuthInitializer: Instance<AuthInitializer>,
    private val dbInitializer: StorageLifecycleManager,
    private val httpConfig: HttpConfig,
    private val bootstrapConfig: BootstrapConfig,
    private val persistentEntityDetector: PersistentEntityDetector
) {

    private val initializationComplete = AtomicBoolean(false)

    fun init(
        @Observes event: StartupEvent
    ) {
        // Init system db
        val exitCode = dbInitializer.init(persistentEntityDetector.getDetectedEntityClasses(StorageLevel.SYSTEM))
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
                Multi.createFrom().iterable(bootstrapConfig.pods())
                    .onItem().transformToUni { podConfig ->
                        val podId = "${httpConfig.baseUri()}${podConfig.name()}"
                        podSetupHelper.createPod(
                            podId,
                            podConfig,
                            Json.encode(podConfig.configuration())
                        )
                    }
                    .concatenate()
                    .onCompletion().invoke { initializationComplete.set(true) }
                    .skipToLast()
            }
            .map {
                Log.info("Kvasir initialization completed successfully.")
                0 // Return exit code 0 on success
            }
            .onFailure().recoverWithItem { err ->
                Log.error("Kvasir initialization failed: ${err.message}", err)
                1 // Return exit code 1 on failure
            }
            .await().indefinitely()

        // Exit on failure, or when exitAfterSetup is set
        if (exitCode != 0 || bootstrapConfig.exitAfterSetup()) {
            Quarkus.asyncExit(exitCode)
        }
    }

    fun isInitialized(): Boolean = initializationComplete.get()

}
