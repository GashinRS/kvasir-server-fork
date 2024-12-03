package kvasir.services.api.storage

import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.RestAssured.`when`
import io.restassured.http.ContentType
import jakarta.inject.Inject
import kvasir.utils.s3.S3Utils
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@QuarkusTest
class StorageApiTest {

    @Inject
    lateinit var minioClient: MinioClient

    @Inject
    @ConfigProperty(name = "kvasir.base-uri")
    lateinit var baseUri: String

    @Test
    fun testPutAndGetResource() {
        // Make sure the bucket exists
        val bucketId = S3Utils.getBucket("${baseUri}test")
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketId).build())

        val content = "Hello, World!"
        given()
            .body(content)
            .contentType(ContentType.TEXT)
            .`when`().put("/test/s3/test.txt")
            .then().statusCode(200)

        val returnedContent = `when`()
            .get("/test/s3/test.txt")
            .then()
            .statusCode(200)
            .extract().body().asString()
        assertEquals(content, returnedContent)
    }

}