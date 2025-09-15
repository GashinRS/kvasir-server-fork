package kvasir.plugins.kg.clickhouse

import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.plugins.kg.clickhouse.schema.QuteSchemaTemplateHelper

@ApplicationScoped
class ClickhouseInitializer(
    private val templateHelper : QuteSchemaTemplateHelper
) {

    fun init(): Uni<Void> {
        Log.debug("Initializing Clickhouse schema for Kvasir system tables...")
        return templateHelper.setupSystemDB()
    }

    fun initializePodSchema(podId: String): Uni<Void> {
        Log.debug("Making sure a Clickhouse schema exists for pod $podId...")
        return templateHelper.setupPodDB(podId)
    }

}