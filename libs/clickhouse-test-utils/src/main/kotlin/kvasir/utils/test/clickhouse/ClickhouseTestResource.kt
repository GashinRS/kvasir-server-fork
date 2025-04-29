package kvasir.utils.test.clickhouse

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.clickhouse.ClickHouseContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile

class ClickhouseTestResource : QuarkusTestResourceLifecycleManager {
    private lateinit var clickhouse: ClickHouseContainer

    private val clickhouseImageName =
        System.getProperty(
            "kvasir.datastore.kg.image-name",
            "altinity/clickhouse-server:24.8.14.10459.altinitystable",
        )

    override fun start(): Map<String, String> {
        val chImage = DockerImageName.parse(clickhouseImageName).asCompatibleSubstituteFor("clickhouse/clickhouse-server")
        clickhouse =
            ClickHouseContainer(chImage)
                .withCopyFileToContainer(
                    MountableFile.forClasspathResource("kvasir.config.xml"),
                    "/etc/clickhouse-server/users.d/kvasir.config.xml",
                )
        clickhouse.start()
        val conf = mutableMapOf<String, String>()
        conf["kvasir.kg.clickhouse.host"] = clickhouse.host
        conf["kvasir.kg.clickhouse.port"] = clickhouse.firstMappedPort.toString()

        return conf
    }

    override fun stop() {
        clickhouse.stop()
    }
}

