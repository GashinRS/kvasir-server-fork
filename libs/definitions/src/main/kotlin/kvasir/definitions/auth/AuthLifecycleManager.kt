package kvasir.definitions.auth

import io.smallrye.mutiny.Uni
import kvasir.definitions.config.BootstrapPodConfig
import kvasir.definitions.kg.Pod

/**
 * An AuthInitializer can be provided by a plugin to hook into the Kvasir lifecycle for setup purposes.
 */
interface AuthLifecycleManager {

    /**
     * Initializes the auth subsystem (global setup).
     */
    fun initialize(): Uni<Void>

    /**.
     * Initializes the auth subsystem for a Pod.
     *
     * @param pod The pod instance, e.g. can be used to retrieve implementation specific auth configuration.
     * @param bootstrapPodConfig The bootstrap configuration used to create the Pod.
     * @return A Uni that completes when the auth subsystem for the Pod is initialized.
     */
    fun initializeForPod(
        pod: Pod,
        bootstrapPodConfig: BootstrapPodConfig
    ): Uni<Void>


    /**
     * Cleans up any resources associated with the given Pod in the auth subsystem.
     *
     * @param podId The ID of the Pod to clean up.
     * @param podName The name of the pod to clean up.
     * @param ownerId Optional ID of the owner of the pod. If not null, can be used to clean up user data.
     * @return A Uni that completes when the cleanup is done.
     */
    fun cleanupForPod(podId: String, podName: String, ownerId: String? = null): Uni<Void>

}