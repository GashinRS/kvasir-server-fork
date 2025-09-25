package kvasir.services.api.storage

import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.RemoveBucketArgs
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.RestAssured.`when`
import io.restassured.http.ContentType
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.core.buffer.Buffer
import io.vertx.mutiny.ext.web.client.WebClient
import jakarta.inject.Inject
import kvasir.definitions.config.KvasirConfig
import kvasir.utils.s3.S3Utils
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import java.util.zip.CRC32
import kotlin.random.Random

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StorageApiTest {

    @Inject
    lateinit var minioClient: MinioClient

    @Inject
    @ConfigProperty(name = KvasirConfig.BASE_URI_PROPERTY)
    lateinit var baseUri: String

    private val podName = UUID.randomUUID().toString()

    lateinit var bucketId: String

    @Inject
    lateinit var vertx: Vertx

    @BeforeAll
    fun setup() {
        // Make sure the bucket exists
        bucketId = S3Utils.getBucket("${baseUri}$podName")
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketId).build())
    }

    @AfterAll
    fun teardown() {
        minioClient.removeBucket(RemoveBucketArgs.builder().bucket(bucketId).build())
    }

    @Test
    fun testPutAndGetResource() {
        val content = "Hello, World!"
        given()
            .body(content)
            .contentType(ContentType.TEXT)
            .`when`().put("/$podName/s3/test.txt")
            .then().statusCode(200)

        val returnedContent = `when`()
            .get("/$podName/s3/test.txt")
            .then()
            .statusCode(200)
            .extract().body().asString()
        assertEquals(content, returnedContent)

        // Delete file
        `when`().delete("/$podName/s3/test.txt").then().statusCode(204)
    }

    @Test
    fun testPutAndGetLargeBinaryResource() {
        val content = Random.nextBytes(25 * 1024 * 1024) // 25 MB
        given()
            .body(content)
            .contentType(ContentType.BINARY)
            .`when`().put("/$podName/s3/large-binary.bin")
            .then().statusCode(200)

        val returnedContent = `when`()
            .get("/$podName/s3/large-binary.bin")
            .then()
            .statusCode(200)
            .extract().body().asByteArray()

        // CRC32 Checksum to verify content integrity
        assertEquals(
            CRC32().apply { this.update(content, 0, content.size) }.value,
            CRC32().apply { this.update(returnedContent, 0, returnedContent.size) }.value
        )

        // Delete file
        `when`().delete("/$podName/s3/large-binary.bin").then().statusCode(204)
    }

    @Test
    fun testPutTooLargeResource() {
        // Try to upload a 60 MB file, which should exceed the default limit (50 MB)
        val content = Random.nextBytes(60 * 1024 * 1024)
        val respStatus =
            WebClient.create(vertx).putAbs("${baseUri}/$podName/s3/too-large-binary.bin").sendBuffer(Buffer.buffer(content))
                .map { resp -> resp.statusCode() }.await().indefinitely()
        assertEquals(413, respStatus)
    }

}