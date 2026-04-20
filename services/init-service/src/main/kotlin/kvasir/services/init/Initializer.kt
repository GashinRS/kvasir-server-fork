package kvasir.services.init

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.introspect.Annotated
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import io.quarkus.logging.Log
import io.quarkus.runtime.Quarkus
import io.quarkus.runtime.Startup
import io.smallrye.common.annotation.Identifier
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import kvasir.definitions.annotations.StorageLevel
import kvasir.definitions.auth.AuthLifecycleManager
import kvasir.definitions.config.BootstrapConfig
import kvasir.definitions.config.HttpConfig
import kvasir.definitions.persistence.RepositoriesLifecycleManager
import kvasir.definitions.reactive.asMulti
import kvasir.definitions.reactive.skipToLast
import kvasir.definitions.reactive.toUni
import kvasir.plugins.messaging.kafka.KafkaMessagingConfig
import kvasir.utils.persistence.PersistentEntityDetector
import kvasir.utils.pod.PodSetupHelper
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.errors.TopicExistsException
import java.util.concurrent.atomic.AtomicBoolean

@ApplicationScoped
class Initializer(
    private val podSetupHelper: PodSetupHelper,
    private val podAuthLifecycleManager: Instance<AuthLifecycleManager>,
    private val repositoriesLifecycleManager: RepositoriesLifecycleManager,
    private val httpConfig: HttpConfig,
    private val bootstrapConfig: BootstrapConfig,
    private val persistentEntityDetector: PersistentEntityDetector,
    private val kafkaMessagingConfig: KafkaMessagingConfig,
    @Identifier("default-kafka-broker")
    private val kafkaBrokerConfig: Map<String, Any>,
) {
    private val initializationComplete = AtomicBoolean(false)

    @Startup
    fun init(): Uni<Void> {
        // Init system db
        return repositoriesLifecycleManager
            .initialize(persistentEntityDetector.getDetectedEntityClasses(StorageLevel.SYSTEM))
            .chain { _ ->
                Log.debug("Initializing Kafka topics")
                // Init Kafka topics
                initializeKafkaTopics()
            }
            // Init auth (global)
            .chain { _ ->
                if (podAuthLifecycleManager.isResolvable) {
                    Log.debug("Initializing global auth configuration")
                    podAuthLifecycleManager.get().initialize()
                } else {
                    Log.debug("No global auth initializer available, skipping global auth setup")
                    Uni.createFrom().voidItem()
                }
            }.chain { _ ->
                // Init pods based on config
                Multi
                    .createFrom()
                    .iterable(bootstrapConfig.pods())
                    .onItem()
                    .transformToUni { podConfig ->
                        val podId = "${httpConfig.baseUri()}${podConfig.name()}"
                        // Create a custom ObjectMapper that bypasses @JsonSerialize annotations of UMAConfig
                        val customMapper =
                            ObjectMapper()
                                .registerModule(Jdk8Module())
                                .setAnnotationIntrospector(
                                    object : JacksonAnnotationIntrospector() {
                                        override fun findSerializer(a: Annotated): Any? {
                                            return null // Ignore @JsonSerialize annotations
                                        }
                                    },
                                )
                        podSetupHelper.createPod(
                            podId,
                            podConfig,
                            customMapper.writeValueAsString(podConfig.configuration()),
                        )
                    }.concatenate()
                    .onCompletion()
                    .invoke { initializationComplete.set(true) }
                    .skipToLast()
            }.map {
                Log.info("Kvasir initialization completed successfully.")
                0 // Return exit code 0 on success
            }.onFailure()
            .recoverWithItem { err ->
                Log.error("Kvasir initialization failed: ${err.message}", err)
                1 // Return exit code 1 on failure
            }.invoke { exitCode ->
                // Exit on failure, or when exitAfterSetup is set
                if (exitCode != 0 || bootstrapConfig.exitAfterSetup()) {
                    Quarkus.asyncExit(exitCode)
                }
            }.replaceWithVoid()
    }

    fun isInitialized(): Boolean = initializationComplete.get()

    fun initializeKafkaTopics(): Uni<Void> {
        val config =
            kafkaBrokerConfig
                .filter {
                    AdminClientConfig.configNames().contains(it.key) || it.key == "tls-configuration-name"
                }.toMap()
        val adminClient = AdminClient.create(config)
        return kafkaMessagingConfig
            .autoCreateTopics()
            .map { topicConfig ->
                NewTopic(
                    topicConfig.topicName(),
                    topicConfig.partitions(),
                    topicConfig.replicationFactor(),
                )
            }.asMulti()
            .onItem()
            .transformToUniAndMerge { topic ->
                adminClient
                    .createTopics(setOf(topic))
                    .all()
                    .toCompletionStage()
                    .toUni()
                    .replaceWithVoid()
                    .onFailure(TopicExistsException::class.java)
                    .recoverWithUni { _ ->
                        Log.debug("Kafka topic '${topic.name()}' already exists, skipping creation.")
                        Uni.createFrom().voidItem()
                    }
            }.skipToLast()
    }
}
