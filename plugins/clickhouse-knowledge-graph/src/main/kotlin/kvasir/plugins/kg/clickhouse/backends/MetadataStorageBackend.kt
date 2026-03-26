package kvasir.plugins.kg.clickhouse.backends

import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.KGType
import kvasir.definitions.kg.TypeRegistry
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.specs.KGTypeQuerySpec
import kvasir.plugins.kg.clickhouse.specs.META_DATA_TABLE
import kvasir.plugins.kg.clickhouse.utils.databaseFromPodId

@ApplicationScoped
class MetadataStorageBackend(
    private val clickhouseClient: ClickhouseClient
) : TypeRegistry {

    override fun getTypeInfo(podId: String): Uni<List<KGType>> {
        return clickhouseClient.query(
            KGTypeQuerySpec(databaseFromPodId(podId)),
            "SELECT type_uri, ARRAY_AGG([property_uri, property_kind, property_ref]) AS properties FROM ${
                databaseFromPodId(
                    podId
                )
            }.$META_DATA_TABLE GROUP BY type_uri"
        )
    }

}