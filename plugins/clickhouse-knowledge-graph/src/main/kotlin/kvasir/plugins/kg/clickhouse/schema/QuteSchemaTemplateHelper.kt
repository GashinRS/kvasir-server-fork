package kvasir.plugins.kg.clickhouse.schema

import io.quarkus.qute.Location
import io.quarkus.qute.Template
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.reactive.skipToLast
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.utils.databaseFromPodId

@ApplicationScoped
class QuteSchemaTemplateHelper(
    private val clickhouseClient: ClickhouseClient,
    @Location("init-repository.sql")
    private val initRepositoryTemplate: Template,
    @Location("init-pod-db.sql")
    private val initPodDBTemplate: Template
) {

    fun setupPodDB(podId: String): Uni<Void> {
        return applyTemplate(initPodDBTemplate, TemplateInput(databaseFromPodId(podId)))
    }

    fun setupRepository(database: String, collectionName: String): Uni<Void> {
        return applyTemplate(initRepositoryTemplate, TemplateInput(database, collectionName))
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

data class TemplateInput(val database: String, val collectionName: String? = null)