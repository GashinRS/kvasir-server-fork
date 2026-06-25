package kvasir.services.api.storage

import com.google.common.hash.Hashing
import io.quarkus.logging.Log
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.RestAssured.`when`
import io.restassured.http.ContentType
import io.vertx.core.http.HttpClosedException
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.core.buffer.Buffer
import io.vertx.mutiny.ext.web.client.WebClient
import jakarta.inject.Inject
import kvasir.definitions.config.HttpConfig
import kvasir.utils.test.commons.AbstractPodTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.zip.CRC32
import kotlin.random.Random

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StorageApiTest : AbstractPodTest() {

    @Inject
    lateinit var vertx: Vertx

    @Inject
    lateinit var httpConfig: HttpConfig

    @Test
    fun testPutAndGetResource() {
        val content = "Hello, World!"
        given()
            .body(content)
            .contentType(ContentType.TEXT)
            .`when`()
            .put("/$podName/s3/test.txt")
            .then()
            .statusCode(200)

        val returnedContent =
            `when`()
                .get("/$podName/s3/test.txt")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asString()
        assertEquals(content, returnedContent)

        // Delete file
        `when`().delete("/$podName/s3/test.txt").then().statusCode(204)
    }

    @Test
    fun testPutWithClientProvidedContentHash() {
        val content = "Client-hashed upload content"
        val contentBytes = content.toByteArray(Charsets.UTF_8)
        val sha256Hash = Hashing.sha256().hashBytes(contentBytes).toString()

        // Upload with client-provided x-amz-content-sha256 header (streaming path)
        given()
            .body(contentBytes)
            .contentType(ContentType.TEXT)
            .header(HEADER_X_AMZ_CONTENT_SHA256, sha256Hash)
            .`when`()
            .put("/$podName/s3/hashed-upload.txt")
            .then()
            .statusCode(200)

        // Verify content was stored correctly
        val returnedContent =
            `when`()
                .get("/$podName/s3/hashed-upload.txt")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asString()
        assertEquals(content, returnedContent)

        // Delete file
        `when`().delete("/$podName/s3/hashed-upload.txt").then().statusCode(204)
    }

    @Test
    fun testPutLargeBinaryWithClientProvidedContentHash() {
        val content = Random.nextBytes(5 * 1024 * 1024) // 5 MB
        val sha256Hash = Hashing.sha256().hashBytes(content).toString()

        // Upload with client-provided hash — body is streamed, not buffered by the proxy
        given()
            .body(content)
            .contentType(ContentType.BINARY)
            .header(HEADER_X_AMZ_CONTENT_SHA256, sha256Hash)
            .`when`()
            .put("/$podName/s3/hashed-large-binary.bin")
            .then()
            .statusCode(200)

        // Download and verify integrity
        val returnedContent =
            `when`()
                .get("/$podName/s3/hashed-large-binary.bin")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asByteArray()

        assertEquals(
            CRC32().apply { this.update(content, 0, content.size) }.value,
            CRC32().apply { this.update(returnedContent, 0, returnedContent.size) }.value,
        )

        // Delete file
        `when`().delete("/$podName/s3/hashed-large-binary.bin").then().statusCode(204)
    }

    @Test
    fun testPutAndGetLargeBinaryResource() {
        val content = Random.nextBytes(25 * 1024 * 1024) // 25 MB
        given()
            .body(content)
            .contentType(ContentType.BINARY)
            .`when`()
            .put("/$podName/s3/large-binary.bin")
            .then()
            .statusCode(200)

        val returnedContent =
            `when`()
                .get("/$podName/s3/large-binary.bin")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asByteArray()

        // CRC32 Checksum to verify content integrity
        assertEquals(
            CRC32().apply { this.update(content, 0, content.size) }.value,
            CRC32().apply { this.update(returnedContent, 0, returnedContent.size) }.value,
        )

        // Delete file
        `when`().delete("/$podName/s3/large-binary.bin").then().statusCode(204)
    }

    @Test
    fun testPutTooLargeResource() {
        // Try to upload a 60 MB file, which should exceed the default limit (50 MB)
        val content = Random.nextBytes(60 * 1024 * 1024)

        val targetUrl = "${httpConfig.baseUri().removeSuffix("/")}/$podName/s3/too-large-binary.bin"
        Log.debug("Trying to upload a too large file to URL: $targetUrl")
        val respStatus =
            WebClient
                .create(vertx)
                .putAbs(targetUrl)
                .sendBuffer(Buffer.buffer(content))
                .map { resp -> resp.statusCode() }
                .onFailure(HttpClosedException::class.java)
                .recoverWithItem(413) // Treat the server closing the connection as the equivalent of a 413
                .await()
                .indefinitely()
        assertEquals(413, respStatus)
    }

    @Test
    fun testPutLargeResourceMultipart() {
        // Try to upload a 120 MB file, this is too large for the configured limit, but should work via multipart
        val content = Random.nextBytes(120 * 1024 * 1024)

        // Create an upload
        val initResponse = given()
            .`when`()
            .post("/$podName/s3/multipart-large-binary.bin?uploads")
            .then()
            .statusCode(200)
            .extract()
        
        val uploadId = initResponse.xmlPath().getString("InitiateMultipartUploadResult.UploadId")

        // Upload the parts
        val partSize = 5 * 1024 * 1024 // 5 MB
        val parts = content.size / partSize + if (content.size % partSize > 0) 1 else 0
        val etags = mutableListOf<String>()
        for (partNumber in 1..parts) {
            val start = (partNumber - 1) * partSize
            val end = minOf(start + partSize, content.size)
            val partContent = content.copyOfRange(start, end)
            val etag = given()
                .body(partContent)
                .contentType(ContentType.BINARY)
                .`when`()
                .put("/$podName/s3/multipart-large-binary.bin?partNumber=$partNumber&uploadId=$uploadId")
                .then()
                .statusCode(200)
                .extract()
                .header("ETag")

            etags.add(etag)
        }

        // Complete the upload
        val completeXml = buildString {
            append("<CompleteMultipartUpload>")
            etags.forEachIndexed { index, etag ->
                append("<Part><PartNumber>${index + 1}</PartNumber><ETag>$etag</ETag></Part>")
            }
            append("</CompleteMultipartUpload>")
        }

        val completeResponse = given()
            .body(completeXml)
            .contentType(ContentType.XML)
            .`when`()
            .post("/$podName/s3/multipart-large-binary.bin?uploadId=$uploadId")
            .then()
            .statusCode(200)
            .extract()
        
        // Extract ETag from XML response body (not from response headers)
        val etagFromXml = completeResponse.xmlPath()
            .getString("CompleteMultipartUploadResult.ETag")
            .removeSurrounding("\"")
        
        // Final etag should end with a dash followed by the number of parts
        assertEquals(parts.toString(), etagFromXml.substringAfterLast("-"))

        val returnedContent =
            `when`()
                .get("/$podName/s3/multipart-large-binary.bin")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asByteArray()

        // CRC32 Checksum to verify content integrity
        assertEquals(
            CRC32().apply { this.update(content, 0, content.size) }.value,
            CRC32().apply { this.update(returnedContent, 0, returnedContent.size) }.value,
        )

        // Delete file
        `when`().delete("/$podName/s3/multipart-large-binary.bin").then().statusCode(204)
    }
}

