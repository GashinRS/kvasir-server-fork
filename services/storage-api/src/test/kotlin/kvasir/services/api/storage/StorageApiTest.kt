package kvasir.services.api.storage

import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.RestAssured.`when`
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@QuarkusTest
class StorageApiTest {

    @Inject
    lateinit var minioClient: MinioClient

    @Test
    fun testPutAndGetResource() {
        // Make sure the bucket exists
        minioClient.makeBucket(MakeBucketArgs.builder().bucket("test").build())

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