package kvasir.plugins.policyagent.openfga

import dev.openfga.sdk.api.client.model.ClientRelationshipCondition
import dev.openfga.sdk.api.client.model.ClientTupleKey
import dev.openfga.sdk.errors.FgaApiValidationError
import idlab.quarkus.ext.pep.openfga.model.util.Codec.Encoder.encUser
import idlab.quarkus.ext.pep.openfga.runtime.cdi.OpenFgaManager
import io.quarkus.logging.Log
import io.quarkus.runtime.LaunchMode
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.Uni
import org.jboss.resteasy.reactive.ClientWebApplicationException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.core.Response
import kvasir.definitions.auth.AuthLifecycleManager
import kvasir.definitions.config.BootstrapPodConfig
import kvasir.definitions.config.GenerateClientConfig
import kvasir.definitions.config.HttpConfig
import kvasir.definitions.kg.Pod
import kvasir.plugins.policyagent.openfga.utils.contextualizeSubject
import kvasir.utils.pod.PodConfigProvider
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.keycloak.admin.client.Keycloak
import org.keycloak.representations.idm.ClientRepresentation
import org.keycloak.representations.idm.CredentialRepresentation
import org.keycloak.representations.idm.RealmRepresentation
import org.keycloak.representations.idm.UserRepresentation
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Duration

private val HTTP_URI_REGEX = "^(http|https)://.*$".toRegex()

@ApplicationScoped
class OpenFgaLifecycleManager(
    private val openFgaManager: OpenFgaManager,
    private val keycloakInstance: Instance<Keycloak>,
    @ConfigProperty(name = "kvasir.auth.keycloak.url")
    private val keycloakServerUrl: String,
    @ConfigProperty(name = "kvasir.auth.keycloak.realm")
    private val kvasirRealm: String,
    private val httpConfig: HttpConfig,
    private val launchMode: LaunchMode
) : AuthLifecycleManager {

    companion object {
        private const val UI_CLIENT_ID = "kvasir-ui"
        private val SSO_IDLE_LIFESPAN = Duration.parse("2h").inWholeSeconds.toInt();
        private val SSO_MAX_LIFESPAN = Duration.parse("8h").inWholeSeconds.toInt();
        private val ACCESS_TOKEN_LIFESPAN = Duration.parse("5m").inWholeSeconds.toInt();
    }

    private fun isDevMode() = launchMode == LaunchMode.DEVELOPMENT

    override fun initialize(): Uni<Void> = VertxContextSupport.executeBlocking {
        Log.debugf("kvasir.plugins.policy-agent.openfga.keycloak-realm: %s", kvasirRealm)
        if (keycloakInstance.isResolvable) {
            Log.debug("Found Keycloak admin client, initializing OIDC server...")
            val keycloak = keycloakInstance.get()

            // Try to access the realm directly instead of listing all realms
            // This allows the admin client to work with only kvasir-realm permissions
            val realmAccessible = try {
                keycloak.realm(kvasirRealm).toRepresentation()
                Log.debug("Kvasir realm '$kvasirRealm' exists and is accessible.")
                true
            } catch (e: ClientWebApplicationException) {
                when (e.response.status) {
                    404 -> {
                        Log.info("Kvasir realm '$kvasirRealm' does not exist, attempting to create it...")
                        try {
                            keycloak.realms().create(RealmRepresentation().apply {
                                this.realm = kvasirRealm
                                this.isEnabled = true
                                this.users = emptyList()
                                this.clients = emptyList()
                                this.refreshTokenMaxReuse = 10
                                this.accessTokenLifespan = ACCESS_TOKEN_LIFESPAN
                                this.ssoSessionMaxLifespan = SSO_MAX_LIFESPAN
                                this.ssoSessionIdleTimeout = SSO_IDLE_LIFESPAN
                            })
                            Log.info("Successfully created Kvasir realm '$kvasirRealm'.")
                            true
                        } catch (createEx: Exception) {
                            throw IllegalStateException(
                                "Kvasir realm '$kvasirRealm' does not exist and the admin client does not have " +
                                    "sufficient permissions to create it. Please create the realm manually or grant " +
                                    "the admin client master-realm:manage-realm permission.",
                                createEx
                            )
                        }
                    }
                    403 -> {
                        throw IllegalStateException(
                            "Admin client does not have access to Kvasir realm '$kvasirRealm'. " +
                                "Please ensure the client has realm-admin role for the '$kvasirRealm' realm.",
                            e
                        )
                    }
                    else -> {
                        Log.errorf(e, "Unexpected HTTP error while checking Kvasir realm '$kvasirRealm'")
                        throw e
                    }
                }
            } catch (e: Exception) {
                Log.errorf(e, "Unexpected error while checking Kvasir realm '$kvasirRealm'")
                throw e
            }

            if (realmAccessible) {
                // Update realm sso session and token timeout settings
                try {
                    val realm = keycloak.realm(kvasirRealm)
                    realm.update(realm.toRepresentation().apply {
                        this.ssoSessionIdleTimeout = SSO_IDLE_LIFESPAN
                        this.ssoSessionMaxLifespan = SSO_MAX_LIFESPAN
                        this.accessTokenLifespan = ACCESS_TOKEN_LIFESPAN
                    })
                    Log.debug("Updated Kvasir realm settings (token lifetimes, SSO session).")
                } catch (e: Exception) {
                    Log.warnf(e, "Could not update Kvasir realm settings. Continuing anyway...")
                }
            }

            // Create public UI client for the pod
            if (keycloak.realm(kvasirRealm).clients().findByClientId(UI_CLIENT_ID).isEmpty()) {
                keycloak.realm(kvasirRealm).clients().create(ClientRepresentation().apply {
                    this.name = UI_CLIENT_ID
                    this.clientId = UI_CLIENT_ID
                    this.isServiceAccountsEnabled = false
                    this.isPublicClient = true
                    this.isDirectAccessGrantsEnabled = false
                    this.authorizationServicesEnabled = false
                    this.redirectUris =
                        listOf(
                            "http://localhost:4200/*",
                            "http://localhost:8081/*",
                            "http://localhost:8080/_ui/*",
                            httpConfig.webclientUri().removeSuffix("/") + "/*"
                        );
                    this.webOrigins = listOf("+");
                    this.attributes = mapOf<String, String>(Pair("pkce.code.challenge.method", "S256"))
                }).checkStatus()
                Log.debug("Initialized default OIDC server (Keycloak) with a client for Kvasir UI...")
            } else {
                Log.debug("Default OIDC server (Keycloak) already has a client for Kvasir UI, skipping initialization...")
            }
        } else {
            Log.debug("Keycloak admin client is not resolvable, skipping OIDC server initialization.")
        }
    }.replaceWithVoid()

    /**
     * Initializes store for pod if store with podName does not exist yet.
     */
    override fun initializeForPod(
        pod: Pod,
        bootstrapPodConfig: BootstrapPodConfig
    ): Uni<Void> {
        val podName = bootstrapPodConfig.name()
        val ownerId = bootstrapPodConfig.ownerUserId().getOrNull() ?: podName
        val podConfig = PodConfigProvider.fromPod(pod)
        val oidcServerUrl = podConfig.auth().oidc().getOrNull()?.serverUrl()
        val ensureUserExists = VertxContextSupport.executeBlocking {
            // In case of embedded Keycloak: Ensure user exists in KC
            // Note: We skip creating users that look like HTTP URIs, as those are likely from external OIDC providers (e.g. Solid WebIDs).
            if (keycloakInstance.isResolvable && oidcServerUrl?.startsWith(keycloakServerUrl) == true && !HTTP_URI_REGEX.containsMatchIn(
                    ownerId
                )
            ) {
                Log.debug("Keycloak admin client is resolvable, ensuring user '${ownerId}' exists in Keycloak...")
                val keycloak = keycloakInstance.get()
                if (keycloak.realm(kvasirRealm).users().search(ownerId, true).isEmpty()) {
                    keycloak.realm(kvasirRealm).users().create(UserRepresentation().apply {
                        this.isEnabled = true
                        this.username = ownerId

                        this.credentials = listOf(CredentialRepresentation().apply {
                            this.type = CredentialRepresentation.PASSWORD
                            this.value = ownerId
                            this.isTemporary = !isDevMode()
                        });

                        if (isDevMode()) {
                            this.email = "$ownerId@example.com"
                            this.firstName = ownerId.replaceFirstChar { it.uppercase() }
                            this.lastName = ownerId.reversed().replaceFirstChar { it.uppercase() }
                        }
                    }).checkStatus()
                    Log.debug("Created user '$ownerId' in Keycloak realm '$kvasirRealm'.")
                }
            } else {
                Log.debug("Keycloak admin client is not resolvable, skipping user creation for '$ownerId'.")
            }
        }.replaceWithVoid()

        return ensureUserExists.flatMap {
            // Then handle openFga stuff
            openFgaManager.storeNames.flatMap {
                if (it.contains(podName)) {
                    Log.debug("OpenFGA store '$podName' already exists, skipping initialization.")
                    Uni.createFrom().voidItem()
                } else {
                    Log.debug("OpenFGA store '$podName' does not exist, initializing it...")
                    openFgaManager.initAuthModel(
                        podName,
                        OpenFgaLifecycleManager::class.java.getResource("/schema.json")
                    )
                        .chain { _ ->
                            // Grant the owner of the pod the "owner" relation on the pod resource.
                            configureOwner(pod.id, podName, ownerId)
                        }
                        .chain { _ ->
                            // Set UMA configuration for the pod (if auto-enabled)
                            if (bootstrapPodConfig.autoRegisterUma()) {
                                configureUma(pod.id, podName)
                            } else {
                                Uni.createFrom().voidItem()
                            }
                        }
                        .chain { _ ->
                            // If a list of preconfigured clients is given, create the clients via Kvasir's Keycloak (if applicable).
                            if (bootstrapPodConfig.generateClients().getOrNull()?.isNotEmpty() == true) {
                                createPreconfiguredClients(podName, bootstrapPodConfig.generateClients().get())
                            } else {
                                Uni.createFrom().voidItem()
                            }
                        }
                }
            }
        }
    }

    override fun cleanupForPod(podId: String, podName: String, ownerId: String?): Uni<Void> {
        val cleanupUser = if (ownerId != null) {
            VertxContextSupport.executeBlocking {
                // In case of embedded Keycloak: Delete user from KC
                if (keycloakInstance.isResolvable) {
                    Log.debug("Keycloak admin client is resolvable, deleting user '$ownerId' from Keycloak...")
                    val keycloak = keycloakInstance.get()
                    val users = keycloak.realm(kvasirRealm).users().search(ownerId, true)
                    users.forEach {
                        keycloak.realm(kvasirRealm).users().delete(it.id).checkStatus()
                        Log.debug("Deleted user '$ownerId' from Keycloak realm '$kvasirRealm'.")
                    }
                } else {
                    Log.debug("Keycloak admin client is not resolvable, skipping user deletion for '$ownerId'.")
                }
            }.replaceWithVoid()
        } else {
            Uni.createFrom().voidItem()
        }

        return cleanupUser.chain { _ ->
            // Remove openfga store associated with the pod
            openFgaManager.storeNames.chain { storeNames ->
                if (storeNames.contains(podName)) {
                    Log.debug("Deleting OpenFGA store '$podName' associated with pod...")
                    openFgaManager.deleteStore(podName)
                } else {
                    Uni.createFrom().voidItem()
                }
            }
        }
    }

    fun configureOwner(podId: String, podName: String, ownerId: String): Uni<Void> {
        Log.debug("Configuring owner '$ownerId' for pod '$podName' with ID '$podId' in OpenFGA...")
        return openFgaManager.addTuples(
            podName, listOf(
                ClientTupleKey()
                    .user(encUser("user:" + contextualizeSubject(ownerId)))
                    .relation("owner")
                    ._object("resource:/${podName}")
            )
        ).onFailure { err -> err is FgaApiValidationError && err.message?.contains("already exists") ?: false }
            .recoverWithUni { _ -> Uni.createFrom().voidItem() } // Ignore if the tuple already exists
    }

    // TODO: How to configure this?
    fun configureUma(podId: String, podName: String): Uni<Void> {
        Log.debug("Configuring UMA for pod '$podName' with ID '$podId' in OpenFGA...")
        // For now, no specific UMA configuration is needed in OpenFGA
        return openFgaManager.addTuples(
            podName, listOf(
                ClientTupleKey()
                    .user("user:*")
                    .relation("owner")
                    ._object("resource:/${podName}")
                    .condition(
                        ClientRelationshipCondition()
                            .name(OpenFgaConstants.EXTERNAL_ACCESS_CONDITION)
                            .context(
                                mapOf(
                                    OpenFgaConstants.EXTERNAL_ACCESS_CONDITION_TYPE to listOf(
                                        "uma",
                                        "http_endpoint"
                                    )
                                )
                            )
                    )
            )
        ).onFailure { err -> err is FgaApiValidationError && err.message?.contains("already exists") ?: false }
            .recoverWithUni { _ -> Uni.createFrom().voidItem() } // Ignore if the tuple already exists
    }

    fun createPreconfiguredClients(podName: String, clients: List<GenerateClientConfig>): Uni<Void> =
        VertxContextSupport.executeBlocking {
            if (keycloakInstance.isResolvable) {
                Log.debug("Keycloak admin client is resolvable, creating preconfigured clients for pod '$podName'...")
                val keycloak = keycloakInstance.get()
                // Create preconfigured clients
                clients.forEach { preconfiguredClient ->
                    if (keycloak.realm(kvasirRealm).clients().findByClientId(preconfiguredClient.clientId())
                            .isEmpty()
                    ) {
                        keycloak.realm(kvasirRealm).clients().create(ClientRepresentation().apply {
                            this.name = preconfiguredClient.clientId()
                            this.clientId = preconfiguredClient.clientId()
                            if (preconfiguredClient.enableServiceAccount()) {
                                this.secret = preconfiguredClient.clientSecret().getOrNull()
                            }
                            this.isServiceAccountsEnabled = preconfiguredClient.enableServiceAccount()
                            this.isPublicClient = !preconfiguredClient.enableServiceAccount()
                            this.isDirectAccessGrantsEnabled = preconfiguredClient.enableServiceAccount()
                            this.authorizationServicesEnabled = false
                            this.redirectUris = preconfiguredClient.redirectUris().getOrNull() ?: emptyList()
                            this.webOrigins = listOf("+")
                            if (preconfiguredClient.enableForcePKCE()) {
                                this.attributes = mapOf<String, String>(Pair("pkce.code.challenge.method", "S256"))
                            }
                        }).checkStatus()
                    }
                }
            } else {
                Log.debug("Keycloak admin client is not resolvable, skipping preconfigured client creation for pod '$podName'.")
            }
        }.chain { _ ->
            val tuples = clients.flatMap { client ->
                if (client.openfga().isPresent) {
                    Log.debug("Constructing OpenFGA tuples for client '${client.clientId()}' in pod '$podName'...")
                    client.openfga().get().relationships().orElse(emptyList()).flatMap { relationship ->
                        relationship.relations().map { relation ->
                            ClientTupleKey()
                                .user("user:" + contextualizeSubject("service-account-${client.clientId()}"))
                                .relation(relation)
                                ._object(
                                    "resource:/${podName}/${
                                        relationship.targetResource().removePrefix("/")
                                    }".removeSuffix("/")
                                )
                        }
                    }
                } else {
                    emptyList()
                }
            }
            if (tuples.isNotEmpty()) {
                // Assign client roles
                Log.debug("Adding OpenFGA tuples for preconfigured clients for pod '$podName'...")
                openFgaManager.addTuples(podName, tuples)
                    .onFailure { err -> err is FgaApiValidationError && err.message?.contains("already exists") ?: false }
                    .recoverWithUni { _ -> Uni.createFrom().voidItem() } // Ignore if the tuple already exists
            } else {
                Uni.createFrom().voidItem()
            }
        }
}

private fun Response.checkStatus() {
    if (status !in 200 until 400) {
        throw RuntimeException("Keycloak request failed with status $status: ${readEntity(String::class.java)}")
    }
}

