package kvasir.plugins.policyagent.openfga

import idlab.quarkus.ext.pep.openfga.runtime.OpenFgaManager
import io.quarkiverse.openfga.client.model.FGAValidationException
import io.quarkiverse.openfga.client.model.RelObject
import io.quarkiverse.openfga.client.model.RelTupleDefinition
import io.quarkiverse.openfga.client.model.RelUser
import io.quarkus.logging.Log
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.core.Response
import kvasir.definitions.auth.AuthInitializer
import kvasir.definitions.config.GenerateClientConfig
import kvasir.definitions.config.KvasirConfig
import kvasir.definitions.kg.Pod
import kvasir.plugins.policyagent.openfga.utils.contextualizeSubject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.keycloak.admin.client.Keycloak
import org.keycloak.representations.idm.ClientRepresentation
import org.keycloak.representations.idm.CredentialRepresentation
import org.keycloak.representations.idm.RealmRepresentation
import org.keycloak.representations.idm.UserRepresentation
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Duration

@ApplicationScoped
class OpenFgaInitializer(
    private val openFgaManager: OpenFgaManager,
    private val keycloakInstance: Instance<Keycloak>,
    @ConfigProperty(name = "quarkus.oidc.client-id", defaultValue = "quarkus-app") private val oidcClientId: String,
    @ConfigProperty(
        name = "quarkus.oidc.credentials.secret",
        defaultValue = "secret"
    ) private val oidcClientSecret: String,
    @ConfigProperty(
        name = "kvasir.plugins.policy-agent.openfga.keycloak-realm",
        defaultValue = "quarkus"
    ) private val kvasirRealm: String,
    @ConfigProperty(name = KvasirConfig.WEBCLIENT_URI_PROPERTY, defaultValue = KvasirConfig.WEBCLIENT_URI_DEFAULT)
    private val webClientUri: String
) : AuthInitializer {

    companion object {
        private const val UI_CLIENT_ID = "kvasir-ui"
        private val SSO_IDLE_LIFESPAN = Duration.parse("2h").inWholeSeconds.toInt();
        private val SSO_MAX_LIFESPAN = Duration.parse("8h").inWholeSeconds.toInt();
        private val ACCESS_TOKEN_LIFESPAN = Duration.parse("5m").inWholeSeconds.toInt();
    }

    override fun initialize(): Uni<Void> = VertxContextSupport.executeBlocking {
        Log.debugf("kvasir.plugins.policy-agent.openfga.keycloak-realm: %s", kvasirRealm)
        if (keycloakInstance.isResolvable) {
            Log.debug("Found Keycloak admin client, initializing OIDC server...")
            val keycloak = keycloakInstance.get()
            // Set global realm settings
            val realmExists = keycloak.realms().findAll().any { kvasirRealm == it.realm }
            if (!realmExists) {
                // Create realm
                Log.debug("Default quarkus realm not present, creating it...")
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
            } else {
                // Update realm sso session and token timeout settings
                val realm = keycloak.realm(kvasirRealm)
                realm.update(realm.toRepresentation().apply {
                    this.ssoSessionIdleTimeout = SSO_IDLE_LIFESPAN
                    this.ssoSessionMaxLifespan = SSO_MAX_LIFESPAN
                    this.accessTokenLifespan = ACCESS_TOKEN_LIFESPAN
                })
            }

            // Create quarkus-app client if not present
            if (keycloak.realm(kvasirRealm).clients().findByClientId(oidcClientId).isEmpty()) {
                Log.debug("Default quarkus-app client not present, creating it... ${oidcClientId}")
                keycloak.realm(kvasirRealm).clients().create(ClientRepresentation().apply {
                    this.clientId = oidcClientId
                    this.name = oidcClientId
                    this.isAlwaysDisplayInConsole = false
                    this.isSurrogateAuthRequired = false
                    this.isEnabled = true
                    this.clientAuthenticatorType = "client-secret"
                    this.secret = oidcClientSecret
                    this.redirectUris = listOf("*")
                    this.isStandardFlowEnabled = true
                    this.isImplicitFlowEnabled = true
                    this.isDirectAccessGrantsEnabled = true
                    this.isServiceAccountsEnabled = true
                    this.isPublicClient = false
                    this.attributes = mapOf(
                        Pair("realm_client", "false"),
                        Pair("post.logout.redirect.uris", "+"),
                    )
                    this.defaultClientScopes = listOf("microprofile-jwt", "basic")
                    this.access = mapOf(
                        Pair("view", true),
                        Pair("configure", true),
                        Pair("manage", true)
                    )
                }).checkStatus()
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
                            webClientUri.removeSuffix("/") + "/*"
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
        podId: String,
        podName: String,
        ownerId: String,
        pod: Pod,
        generateClients: List<GenerateClientConfig>?
    ): Uni<Void> {
        val ensureUserExists = VertxContextSupport.executeBlocking {
            // In case of embedded Keycloak: Ensure user exists in KC
            if (keycloakInstance.isResolvable) {
                Log.debug("Keycloak admin client is resolvable, ensuring user '${ownerId}' exists in Keycloak...")
                val keycloak = keycloakInstance.get()
                if (keycloak.realm(kvasirRealm).users().search(ownerId, true).isEmpty()) {
                    keycloak.realm(kvasirRealm).users().create(UserRepresentation().apply {
                        this.isEnabled = true
                        this.username = ownerId

                        this.credentials = listOf(CredentialRepresentation().apply {
                            this.type = CredentialRepresentation.PASSWORD
                            this.value = ownerId
                            this.isTemporary = true
                        });
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
                        OpenFgaInitializer::class.java.getResource("/schema.dsl")
                    )
                        .chain { _ ->
                            // Grant the owner of the pod the "owner" relation on the pod resource.
                            configureOwner(pod.id, podName, ownerId)
                        }
                        .chain { _ ->
                            // If a list of preconfigured clients is given, create the clients via Kvasir's Keycloak (if applicable).
                            if (generateClients?.isNotEmpty() == true) {
                                createPreconfiguredClients(podName, generateClients)
                            } else {
                                Uni.createFrom().voidItem()
                            }
                        }
                }
            }
        }
    }

    override fun cleanupForPod(podId: String, podName: String, ownerId: String?): Uni<Void> {
        val cleanupUser = if(ownerId != null) {
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
                    openFgaManager.getStoreClient(podName).chain { storeClient -> storeClient.delete() }
                } else {
                    Uni.createFrom().voidItem()
                }
            }
        }
    }

    fun configureOwner(podId: String, podName: String, ownerId: String): Uni<Void> {
        Log.debug("Configuring owner '$ownerId' for pod '$podName' with ID '$podId' in OpenFGA...")
        return openFgaManager.addTuples(
            podName, setOf(
                RelTupleDefinition.builder()
                    .user(RelUser.of("user", contextualizeSubject(ownerId)))
                    .relation("owner")
                    .`object`(RelObject.of("resource", "/$podName"))
                    .build()
            )
        ).onFailure { err -> err is FGAValidationException && err.message?.contains("already exists") ?: false }
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
                            RelTupleDefinition.builder()
                                .user(RelUser.of("user", contextualizeSubject("service-account-${client.clientId()}")))
                                .relation(relation)
                                .`object`(
                                    RelObject.of(
                                        "resource",
                                        "/$podName/${relationship.targetResource().removePrefix("/")}".removeSuffix("/")
                                    )
                                )
                                .build()
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
                    .onFailure { err -> err is FGAValidationException && err.message?.contains("already exists") ?: false }
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
