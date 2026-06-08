package kvasir.utils.test.commons

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.net.ServerSocket

/**
 * A Quarkus test resource that allocates a random free HTTP port before Quarkus starts
 * and injects it into both `quarkus.http.test-port` and `kvasir.http.base-uri`.
 */
class DynamicPortTestResource : QuarkusTestResourceLifecycleManager {
    private var port = 0

    override fun start(): Map<String, String> {
        port = allocateFreePort()
        return mapOf(
            "quarkus.http.test-port" to port.toString(),
            "kvasir.http.base-uri" to "http://localhost:$port/",
        )
    }

    override fun stop() {}

    private fun allocateFreePort(): Int = ServerSocket(0).use { it.localPort }
}
