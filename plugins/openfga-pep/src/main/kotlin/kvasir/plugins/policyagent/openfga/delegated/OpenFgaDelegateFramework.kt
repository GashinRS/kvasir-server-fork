package kvasir.plugins.policyagent.openfga.delegated

import dev.openfga.sdk.api.client.model.ClientTupleKey
import idlab.quarkus.ext.pep.openfga.model.hooks.OpenFgaCheckResult
import idlab.quarkus.ext.pep.openfga.model.hooks.PostOpenFgaCheckHook
import idlab.quarkus.ext.pep.openfga.runtime.cdi.OpenFgaManager
import io.quarkus.logging.Log
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.vertx.core.http.HttpServerRequest
import jakarta.enterprise.inject.spi.CDI
import jakarta.ws.rs.ClientErrorException
import jakarta.ws.rs.core.HttpHeaders
import kvasir.plugins.policyagent.openfga.OpenFgaConstants
import kvasir.plugins.policyagent.openfga.utils.addWwwAuthenticateValue
import kvasir.utils.pod.PodConfigProvider
import kotlin.jvm.optionals.getOrNull

class PostCheckDelegator : PostOpenFgaCheckHook {

    // Delegation targets, should be sorted by @Priority
    private val delegationTargets = CDI.current().select(DelegatedPolicyEnforcer::class.java)

    private val openFgaManager = CDI.current().select(OpenFgaManager::class.java)

    private val podConfigProvider = CDI.current().select(PodConfigProvider::class.java)

    override fun handle(request: HttpServerRequest, result: OpenFgaCheckResult): Uni<Void> {
        return handle(
            request,
            result.storeName,
            result.checkedTuple,
            result.contextualTuples,
            result.context,
            result.isAllowed
        )
    }

    fun handle(
        request: HttpServerRequest,
        storeName: String,
        checkedTuple: ClientTupleKey,
        contextualTuples: Collection<ClientTupleKey>,
        context: Map<String, Any?>,
        isAllowed: Boolean
    ): Uni<Void> {
        return if (isAllowed) {
            // If access is allowed, no need to delegate further
            Uni.createFrom().voidItem()
        } else {
            if (delegationTargets.count() > 0) {
                // Delegate to delegation targets in order of priority
                Multi.createFrom().iterable(delegationTargets).onItem()
                    .transformToUniAndConcatenate { delegationTarget ->
                        // Check OpenFGA if the decision can be delegated to this target
                        val keyValContext = context.filterKeys { key -> key != OpenFgaConstants.CHECK_TYPE } + mapOf(
                            OpenFgaConstants.CHECK_TYPE to delegationTarget.id()
                        )
                        val delegatedCheckParams = DelegatedCheckParams(
                            request,
                            storeName,
                            checkedTuple,
                            contextualTuples,
                            keyValContext
                        )
                        openFgaManager.get()
                            .check(storeName, checkedTuple, contextualTuples, keyValContext)
                            .chain { applicable ->
                                if (applicable) {
                                    // Delegation target is applicable, check if it allows the request
                                    delegationTarget.isAllowed(delegatedCheckParams)
                                        .onFailure().recoverWithItem { err ->
                                            Log.warn(
                                                "Delegated policy enforcer '${delegationTarget.id()}' failed, treating as denial",
                                                err
                                            )
                                            false
                                        }
                                } else {
                                    // Not applicable, skip to next delegation target
                                    Uni.createFrom().item(false)
                                }
                            }
                    }
                    // Execute the flow until the first allowed result is found
                    .filter { it }.collect().first()
                    .chain { result ->
                        if (result != null) {
                            // Access was granted by a delegation target
                            Uni.createFrom().voidItem()
                        } else {
                            // None of the delegation targets granted access, deny the request
                            Uni.createFrom().failure(ClientErrorException(401))
                        }
                    }
            } else {
                // Deny request if no delegation targets are available
                Uni.createFrom().failure(ClientErrorException(401))
            }
                .onFailure(ClientErrorException::class.java).recoverWithUni { err ->
                    podConfigProvider.get().getPodConfigByName(storeName)
                        .chain { podConfig ->
                            val oidcAuthServerUrl = podConfig?.auth()?.oidc()?.getOrNull()?.serverUrl()
                            if (oidcAuthServerUrl != null) {
                                // Add the base WWW-Authenticate header
                                request.response().addWwwAuthenticateValue(
                                    "Bearer as_uri=\"$oidcAuthServerUrl\""
                                )
                            }
                            Uni.createFrom().failure(err)
                        }
                }
        }
    }
}

interface DelegatedPolicyEnforcer {

    fun id(): String

    fun isAllowed(params: DelegatedCheckParams): Uni<Boolean>

}

data class DelegatedCheckParams(
    val request: HttpServerRequest,
    val storeName: String,
    val checkedTuple: ClientTupleKey,
    val contextualTuples: Collection<ClientTupleKey>,
    val context: Map<String, Any?>
)

