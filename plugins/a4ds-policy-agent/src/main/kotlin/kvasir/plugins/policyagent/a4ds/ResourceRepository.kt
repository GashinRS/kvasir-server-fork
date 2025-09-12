package kvasir.plugins.policyagent.a4ds

import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.persistence.Repository
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.persistence.AbstractRepository
import kvasir.plugins.kg.clickhouse.specs.EntityQuerySpec
import kvasir.plugins.kg.clickhouse.specs.EntityWriteSpec
import kvasir.plugins.kg.clickhouse.specs.SYSTEM_DB

/**
 * TODO: Avoid direct dependency on implementation specific module, by having a system for dynamically registering repository implementations with CDI.
 */
@ApplicationScoped
class ResourceRepositoryProvider(private val clickhouseClient: ClickhouseClient) {
    fun create(): ResourceRepository {
        return ResourceRepository(clickhouseClient)
    }
}

class ResourceRepository(clickhouseClient: ClickhouseClient) : Repository<Resource>,
    AbstractRepository<Resource>(
        clickhouseClient,
        EntityQuerySpec(Resource::class, SYSTEM_DB, "a4ds_resources"),
        EntityWriteSpec(Resource::class, SYSTEM_DB, "a4ds_resources"),
    )