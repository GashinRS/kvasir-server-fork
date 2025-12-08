package kvasir.utils.test.commons

import io.vertx.core.json.Json
import jakarta.inject.Inject
import kvasir.utils.pod.PodSetupHelper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractPodTest(
    val podName: String = UUID.randomUUID().toString(),
    val ownerUserId: String? = null
) {
    @Inject
    lateinit var testHelpers: TestHelpers

    @Inject
    lateinit var podSetupHelper: PodSetupHelper

    val config = TestPodConfig(podName, ownerUserId ?: "alice")

    lateinit var podUri: String

    @BeforeAll
    fun setup() {
        podUri = testHelpers.getPodUri(podName)
        podSetupHelper.createPod(podUri, config, Json.encode(config.configuration()), errorWhenExists = true).await()
            .indefinitely()
    }

    @AfterAll
    fun teardown() {
        podSetupHelper.deletePod(podUri, config, deleteData = true, ownerUserId != null).await().indefinitely()
    }
}