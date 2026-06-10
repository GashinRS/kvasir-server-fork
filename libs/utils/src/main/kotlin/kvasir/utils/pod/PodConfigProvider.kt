package kvasir.utils.pod

import io.smallrye.config.SmallRyeConfigBuilder
import io.smallrye.config.source.yaml.YamlConfigSource
import io.smallrye.mutiny.Uni
import io.vertx.core.http.HttpServerRequest
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.config.HttpConfig
import kvasir.definitions.config.PodConfig
import kvasir.definitions.config.PodConfigOverride
import kvasir.definitions.kg.Pod
import kvasir.definitions.persistence.RepositoryFactory
import org.yaml.snakeyaml.Yaml

@ApplicationScoped
class PodConfigProvider(
    val config: HttpConfig,
    val podConfig: PodConfig,
    val repositoryFactory: RepositoryFactory
) {

    companion object {
        fun deserializePodConfigOverride(input: String): PodConfigOverride {
            val config = SmallRyeConfigBuilder().withSources(
                YamlConfigSource("registerPodInputConfigOverride", input)
            ).withMapping(PodConfigOverride::class.java, "").build()
            return config.getConfigMapping(PodConfigOverride::class.java, "")
        }

        fun deserializePodConfig(input: String): PodConfig {
            val config = SmallRyeConfigBuilder().withSources(
                YamlConfigSource("registerPodInputConfig", input)
            ).withMapping(PodConfig::class.java, "").build()
            return config.getConfigMapping(PodConfig::class.java, "")
        }

        fun fromPod(pod: Pod): PodConfig {
            val overrideMap = mapOf("kvasir" to mapOf("pod" to pod.getConfigAsJson()))
            val configInstance = SmallRyeConfigBuilder()
                .addDefaultSources()
                .addDiscoveredSources()
                .addSystemSources()
                .addPropertiesSources()
                .addDefaultInterceptors()
                .addDiscoveredCustomizers()
                .addDiscoveredInterceptors()
                .addDiscoveredSecretKeysHandlers()
                .addDiscoveredValidator()
                .addDiscoveredConverters()
                .withSources(YamlConfigSource("podConfig", Yaml().dump(overrideMap), Int.MAX_VALUE))
                .withMapping(PodConfig::class.java)
                .build()
            return configInstance.getConfigMapping(PodConfig::class.java)
        }
    }

    fun getPodConfigById(podId: String): Uni<PodConfig?> {
        return repositoryFactory.getRepository(Pod::class)
            .findById(podId)
            .map { pod ->
                pod?.let { fromPod(it) }
            }
    }

    fun getPodConfigByName(podName: String): Uni<PodConfig?> {
        return getPodConfigById("${config.baseUri().removeSuffix("/")}/${podName}")
    }

    fun getPodConfigForRequestContext(request: HttpServerRequest): Uni<PodConfig> {
        return request.path().split('/').filterNot { it.isBlank() }
            .takeIf { it.isNotEmpty() }?.let { pathItems ->
                val podName = pathItems.first()
                return getPodConfigByName(podName)
                    .onItem().ifNull().continueWith(podConfig)
                    .map { it!! }
            }
            ?: Uni.createFrom().item(podConfig)
    }

    fun notifyPodConfigUpdated(podId: String): Uni<Void> {
        return Uni.createFrom().voidItem()
    }

}