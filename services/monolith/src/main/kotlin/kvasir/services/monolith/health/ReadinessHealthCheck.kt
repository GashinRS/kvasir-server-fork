package kvasir.services.monolith.health

import jakarta.enterprise.context.ApplicationScoped
import kvasir.services.monolith.bootstrap.Initializer
import org.eclipse.microprofile.health.HealthCheck
import org.eclipse.microprofile.health.HealthCheckResponse
import org.eclipse.microprofile.health.Readiness

@Readiness
@ApplicationScoped
class ReadinessHealthCheck(private val initializer: Initializer) : HealthCheck {

    override fun call(): HealthCheckResponse {
        return if (initializer.isInitialized()) {
            HealthCheckResponse.up("Pod initialization")
        } else {
            HealthCheckResponse.down("Pod initialization")
        }
    }
}