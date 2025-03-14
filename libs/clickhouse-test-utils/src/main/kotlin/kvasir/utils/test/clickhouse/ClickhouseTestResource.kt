package kvasir.utils.test.clickhouse

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.clickhouse.ClickHouseContainer
import org.testcontainers.utility.MountableFile

class ClickhouseTestResource : QuarkusTestResourceLifecycleManager {
    private lateinit var clickhouse: ClickHouseContainer

    private val clickhouseImage = System.getProperty("kvasir.datastore.kg.image-name", "clickhouse/clickhouse-server:24.8.11.5")

    override fun start(): Map<String, String> {
        clickhouse = ClickHouseContainer(clickhouseImage)
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("kvasir.config.xml"),
                "/etc/clickhouse-server/users.d/kvasir.config.xml"
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