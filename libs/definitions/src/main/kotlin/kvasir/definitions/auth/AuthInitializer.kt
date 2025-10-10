package kvasir.definitions.auth

import io.smallrye.mutiny.Uni
import kvasir.definitions.config.GenerateClientConfig
import kvasir.definitions.kg.Pod
import kvasir.definitions.rdf.JSONObject

/**
 * An AuthInitializer can be provided by a plugin to hook into the Kvasir lifecycle for setup purposes.
 */
interface AuthInitializer {

    /**
     * Initializes the auth subsystem (global setup).
     */
    fun initialize(): Uni<Void>

    /**.
     * Initializes the auth subsystem for a Pod.
     *
     * @param podName The name of the pod to initialize.
     * @param ownerId The ID of the owner of the pod. This is used to set up the initial owner user for the pod (if Kvasir's built-in Keycloak server is used).
     * @param pod The pod instance, e.g. can be used to retrieve implementation specific auth configuration.
     * @param generateClients Optional list of clients to be generated. Only works when Kvasir's built-in Keycloak server is used for the pod.
     * @return A Uni that completes when the auth subsystem for the Pod is initialized.
     */
    fun initializeForPod(
        podId: String,
        podName: String,
        ownerId: String,
        pod: Pod,
        generateClients: List<GenerateClientConfig>? = null
    ): Uni<Void>


    /**
     * TODO: rename interface to AuthLifecycleManager (or similar) or split into separate interface
     * Cleans up any resources associated with the given Pod in the auth subsystem.
     *
     * @param podId The ID of the Pod to clean up.
     * @param podName The name of the pod to clean up.
     * @param ownerId Optional ID of the owner of the pod. If not null, can be used to clean up user data.
     * @return A Uni that completes when the cleanup is done.
     */
    fun cleanupForPod(podId: String, podName: String, ownerId: String? = null): Uni<Void>

}