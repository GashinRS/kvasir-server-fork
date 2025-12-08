package kvasir.plugins.policyagent.openfga.delegated.external

import io.quarkus.arc.Unremovable
import io.smallrye.mutiny.Uni
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.ext.web.client.WebClient
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.config.ApiKeySendVia
import kvasir.plugins.policyagent.openfga.delegated.DelegatedCheckParams
import kvasir.plugins.policyagent.openfga.delegated.DelegatedPolicyEnforcer
import kvasir.utils.pod.PodConfigProvider
import kotlin.jvm.optionals.getOrNull

@ApplicationScoped
@Unremovable // DelegatedPolicyEnforcers are discovered via programmatic lookup, so CDI may think they are unused and remove them otherwise
class HttpEndpointDelegatedPolicyEnforcer(vertx: Vertx, private val podConfigProvider: PodConfigProvider) :
    DelegatedPolicyEnforcer {

    private val webClient = WebClient.create(vertx)

    override fun id() = "http_endpoint"

    override fun isAllowed(params: DelegatedCheckParams): Uni<Boolean> {
        return podConfigProvider.getPodConfigForRequestContext(params.request)
            .chain { podConfig ->
                val httpEndpointPolicyEnforcerConfig = podConfig.auth().httpEndpointPolicyEnforcer().getOrNull()
                if (httpEndpointPolicyEnforcerConfig != null) {
                    webClient.postAbs(httpEndpointPolicyEnforcerConfig.url())
                        .apply {
                            // If an API key is configured...
                            if (httpEndpointPolicyEnforcerConfig.apiKey().isPresent) {
                                val apiKeyConfig = httpEndpointPolicyEnforcerConfig.apiKey().get()
                                val keyName = apiKeyConfig.keyName()
                                val keyValue = apiKeyConfig.keyValue()
                                when (apiKeyConfig.sendVia()) {
                                    ApiKeySendVia.header -> this.putHeader(keyName, keyValue)
                                    ApiKeySendVia.query -> this.addQueryParam(keyName, keyValue)
                                }
                            }
                            // If basic auth is configured...
                            if (httpEndpointPolicyEnforcerConfig.basicAuth().isPresent) {
                                this.basicAuthentication(
                                    httpEndpointPolicyEnforcerConfig.basicAuth().get().username(),
                                    httpEndpointPolicyEnforcerConfig.basicAuth().get().password()
                                )
                            }
                        }
                        .sendJson(
                            HttpEndpointPolicyEnforcerInput(
                                params.checkedTuple.user.substringAfter(":"),
                                params.checkedTuple.relation,
                                params.request.absoluteURI(),
                                params.request.headers().entries().associate { it.key to it.value },
                                params.request.method().name()
                            )
                        )
                        .map { resp ->
                            if (resp.statusCode() == 200) {
                                val pepOutput =
                                    resp.bodyAsJsonObject().mapTo(HttpEndpointPolicyEnforcerOutput::class.java)
                                pepOutput.allowed
                            } else {
                                false
                            }
                        }
                } else {
                    Uni.createFrom().item(false)
                }
            }
            .onFailure().recoverWithItem(false)
    }
}

@GenerateNoArgConstructor
data class HttpEndpointPolicyEnforcerInput(
    var principal: String,
    var requiredPermission: String,
    var requestUri: String,
    var requestHeaders: Map<String, String>,
    var requestMethod: String
)

@GenerateNoArgConstructor
data class HttpEndpointPolicyEnforcerOutput(var allowed: Boolean)