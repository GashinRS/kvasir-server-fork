package kvasir.plugins.kg.clickhouse.schema

import io.quarkus.qute.Location
import io.quarkus.qute.Template
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.reactive.skipToLast
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.specs.SYSTEM_DB
import kvasir.plugins.kg.clickhouse.utils.databaseFromPodId

@ApplicationScoped
class QuteSchemaTemplateHelper(
    private val clickhouseClient: ClickhouseClient,
    @Location("init-system-db.sql")
    private val initSystemDBTemplate: Template,
    @Location("init-pod-db.sql")
    private val initPodDBTemplate: Template
) {

    fun setupPodDB(podId: String): Uni<Void> {
        return applyTemplate(initPodDBTemplate, TemplateInput(databaseFromPodId(podId)))
    }

    fun setupSystemDB(): Uni<Void> {
        return applyTemplate(initSystemDBTemplate, TemplateInput(SYSTEM_DB))
    }

    private fun applyTemplate(template: Template, input: TemplateInput): Uni<Void> {
        val sql = template.data(
            "cfg",
            input
        ).render()
        // Split sql statements by semicolon and remove empty statements
        val statements = sql.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // Execute the statements sequentially
        return Multi.createFrom().iterable(statements)
            .onItem().transformToUniAndConcatenate { statement ->
                clickhouseClient.execute(statement)
            }.skipToLast()
    }

}

data class TemplateInput(val database: String)