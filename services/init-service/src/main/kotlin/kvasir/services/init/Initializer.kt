package kvasir.services.init

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.introspect.Annotated
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import io.quarkus.logging.Log
import io.quarkus.runtime.Quarkus
import io.quarkus.runtime.Startup
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
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

    @Startup
    fun init(): Uni<Void> {
        // Init system db
        return dbInitializer.init(persistentEntityDetector.getDetectedEntityClasses(StorageLevel.SYSTEM))
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
                        // Create a custom ObjectMapper that bypasses @JsonSerialize annotations of UMAConfig
                        val customMapper = ObjectMapper()
                            .registerModule(Jdk8Module())
                            .setAnnotationIntrospector(object : JacksonAnnotationIntrospector() {
                                override fun findSerializer(a: Annotated): Any? {
                                    return null // Ignore @JsonSerialize annotations
                                }
                            })
                        podSetupHelper.createPod(
                            podId,
                            podConfig,
                            customMapper.writeValueAsString(podConfig.configuration())
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
            .invoke { exitCode ->
                // Exit on failure, or when exitAfterSetup is set
                if (exitCode != 0 || bootstrapConfig.exitAfterSetup()) {
                    Quarkus.asyncExit(exitCode)
                }
            }
            .replaceWithVoid()
    }

    fun isInitialized(): Boolean = initializationComplete.get()

}
