package kvasir.plugins.policyagent.uma

import io.quarkus.arc.properties.IfBuildProperty
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.AuthConfiguration
import kvasir.definitions.kg.ClientConfiguration
import kvasir.definitions.kg.PodAuthInitializer

@IfBuildProperty(name = Constants.SOLID_UMA_POLICY_AGENT_ENABLED, stringValue = "true")
@ApplicationScoped
class StaticPodAuthInitializer : PodAuthInitializer {
    override fun initialize(podId: String, podName: String, preconfiguredClients: List<ClientConfiguration>): Uni<AuthConfiguration> {
        return Uni.createFrom().item(AuthConfiguration("http://$podName-as.example.org", "$podName-client", "secret"))
    }
}