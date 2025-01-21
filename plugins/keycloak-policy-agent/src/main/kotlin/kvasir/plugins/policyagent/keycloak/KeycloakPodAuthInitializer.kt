package kvasir.plugins.policyagent.keycloak

import com.google.common.hash.Hashing
import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import io.vertx.mutiny.core.Vertx
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import kvasir.definitions.kg.AuthConfiguration
import kvasir.definitions.kg.PodAuthInitializer
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.resteasy.reactive.ClientWebApplicationException
import org.keycloak.admin.client.KeycloakBuilder
import org.keycloak.representations.idm.*
import org.keycloak.representations.idm.authorization.PolicyEnforcementMode
import org.keycloak.representations.idm.authorization.ResourcePermissionRepresentation
import org.keycloak.representations.idm.authorization.ResourceServerRepresentation
import org.keycloak.representations.idm.authorization.RolePolicyRepresentation
import java.net.URI
import java.util.*
import kotlin.time.Duration

private const val CLIENT_ID = "kvasir-server"
private const val UI_CLIENT_ID = "kvasir-ui"
private const val POD_OWNER_ROLE_NAME = "owner"
private const val DEFAULT_RESOURCE_NAME = "Default Resource"
private const val DEFAULT_POLICY_NAME = "Default Policy"
private const val DEFAULT_PERMISSION_NAME = "Default Permission"


@ApplicationScoped
class KeycloakPodAuthInitializer(
    private val vertx: Vertx,
    @ConfigProperty(name = "quarkus.oidc.auth-server-url")
    defaultRealmUri: String
) : PodAuthInitializer {

    private val SSO_IDLE_LIFESPAN = Duration.parse("2h").inWholeSeconds.toInt();
    private val SSO_MAX_LIFESPAN = Duration.parse("8h").inWholeSeconds.toInt();
    private val ACCESS_TOKEN_LIFESPAN = Duration.parse("5m").inWholeSeconds.toInt();


    private val keycloakHostUrl = URI(defaultRealmUri).let { "${it.scheme}://${it.authority}" };
    private val keycloak = KeycloakBuilder.builder().serverUrl(keycloakHostUrl).realm("master")
        .clientId("admin-cli").grantType("password").username("admin").password("admin").build()
    private val realmsBaseUri = defaultRealmUri.substringBeforeLast("/")

    override fun initialize(
        podId: String,
        podName: String
    ): Uni<AuthConfiguration> = vertx.executeBlocking {
        // Try creating a realm
        buildRealm(podName)

        // Return the auth configuration regardless
        AuthConfiguration(
            serverUrl = "$realmsBaseUri/$podName",
            clientId = CLIENT_ID,
            clientSecret = keycloak.realm(podName).clients().findByClientId(CLIENT_ID).first().secret
        )
    }

    /**
     * Create a realm for the pod
     */
    private fun buildRealm(podName: String) = try {
        keycloak.realms().create(RealmRepresentation().apply {
            this.realm = podName
            this.isEnabled = true
            this.ssoSessionIdleTimeout = SSO_IDLE_LIFESPAN
            this.ssoSessionMaxLifespan = SSO_MAX_LIFESPAN
            this.accessTokenLifespan = ACCESS_TOKEN_LIFESPAN
        })

        // Realm role
        keycloak.realm(podName).roles().create(RoleRepresentation().apply {
            this.name = POD_OWNER_ROLE_NAME
        });

        // Create default user (credentials: user:user)
        keycloak.realm(podName).users().create(UserRepresentation().apply {
            this.isEnabled = true;
            this.username = podName.lowercase();
            this.email = "$podName@example.org";
            this.isEmailVerified = true
            this.firstName = podName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            this.lastName = "Demo"
            this.credentials = listOf(
                CredentialRepresentation().apply {
                    this.isTemporary = true;
                    this.type = "password"
                    this.value = podName.lowercase()
                    this.userLabel = "Password"
                }
            )
        })

        // Add ownerRole to User realm roles
        val ownerRole = keycloak.realm(podName).roles().list().find { it.name == POD_OWNER_ROLE_NAME }
        val defaultUser = keycloak.realm(podName).users().searchByUsername(podName.lowercase(), true).first()
        keycloak.realm(podName).users().get(defaultUser.id).roles().realmLevel().add(listOf(ownerRole))

        val secret =
            Hashing.farmHashFingerprint64().hashString(UUID.randomUUID().toString(), Charsets.UTF_8).toString()

        // Create a confidential client for the pod
        keycloak.realm(podName).clients().create(ClientRepresentation().apply {
            this.name = CLIENT_ID
            this.clientId = CLIENT_ID
            this.isServiceAccountsEnabled = true
            this.secret = secret
            this.isDirectAccessGrantsEnabled = true
            this.authorizationServicesEnabled = true
            this.authorizationSettings = ResourceServerRepresentation().apply {
                this.policyEnforcementMode = PolicyEnforcementMode.ENFORCING
            }
        }).checkStatus()

        // Create public UI client for the pod
        keycloak.realm(podName).clients().create(ClientRepresentation().apply {
            this.name = UI_CLIENT_ID
            this.clientId = UI_CLIENT_ID
            this.isServiceAccountsEnabled = false
            this.isPublicClient = true
            this.isDirectAccessGrantsEnabled = false
            this.authorizationServicesEnabled = false
            this.redirectUris =
                listOf<String>("http://localhost:4200/*", "http://localhost:3000/*", "http://localhost:8081/*");
            this.webOrigins = listOf<String>("+");
            this.attributes = mapOf<String, String>(Pair("pkce.code.challenge.method", "S256"))
        }).checkStatus()

        val clientRepresentation = keycloak.realm(podName).clients().findByClientId(CLIENT_ID).first()
        val clientResource = keycloak.realm(podName).clients().get(clientRepresentation.id)

        val defaultResourceRepresentation =
            clientResource.authorization().resources().findByName(DEFAULT_RESOURCE_NAME).first()

        // Delete default policy
        clientResource.authorization().policies().findByName(DEFAULT_POLICY_NAME)?.let {
            clientResource.authorization().policies().policy(it.id).remove()
        }

        clientResource.authorization().policies().role().create(RolePolicyRepresentation().apply {
            this.name = DEFAULT_POLICY_NAME
            this.roles = setOf(RolePolicyRepresentation.RoleDefinition().apply {
                this.id = keycloak.realm(podName).roles().list().find { it.name == POD_OWNER_ROLE_NAME }!!.id
                this.isRequired = true
            }
            )
        }).checkStatus()
        val defaultPolicyRepresentation = clientResource.authorization().policies().findByName(DEFAULT_POLICY_NAME)

        clientResource.authorization().permissions().resource().create(ResourcePermissionRepresentation().apply {
            this.name = DEFAULT_PERMISSION_NAME
            this.addResource(defaultResourceRepresentation.id)
            this.addPolicy(defaultPolicyRepresentation.id)
        }).checkStatus()
    } catch (ex: ClientWebApplicationException) {
        if (409 == ex.response.status) {
            Log.warn("Realm '$podName' already exists. Skipping realm creation...")
        } else {
            Log.error("Error creating realm '$podName!'", ex)
        }
    }
}

private fun Response.checkStatus() {
    if (status !in 200 until 400) {
        throw RuntimeException("Keycloak request failed with status $status: ${readEntity(String::class.java)}")
    }
}
