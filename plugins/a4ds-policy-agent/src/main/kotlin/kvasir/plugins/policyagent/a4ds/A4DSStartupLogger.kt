package kvasir.plugins.policyagent.a4ds

import io.quarkus.logging.Log
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes

@ApplicationScoped
class A4DSStartupLogger {

    fun onStart(@Observes e: StartupEvent) {
        Log.info("A4DS Policy Agent plugin enabled!")
    }

}