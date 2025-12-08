package kvasir.plugins.policyagent.openfga

import io.quarkus.logging.Log
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes


@ApplicationScoped
class OpenFgaStartupLogger {

    fun onStart(@Observes e: StartupEvent) {
        Log.info("OpenFga Policy Agent plugin enabled!")
    }

}