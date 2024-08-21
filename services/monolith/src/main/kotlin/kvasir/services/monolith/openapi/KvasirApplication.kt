package kvasir.services.monolith.openapi

import jakarta.ws.rs.core.Application
import kvasir.definitions.openapi.ApiDocTags
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition
import org.eclipse.microprofile.openapi.annotations.info.Info
import org.eclipse.microprofile.openapi.annotations.info.License
import org.eclipse.microprofile.openapi.annotations.tags.Tag

@OpenAPIDefinition(
    info = Info(
        title = "Kvasir API",
        version = "0.1.0",
        license = License(name = "Apache 2.0", url = "https://www.apache.org/licenses/LICENSE-2.0.html")
    ),
    tags = [
        Tag(
            name = ApiDocTags.KNOWLEDGE_GRAPH_API,
            description = "API for interfacing with the knowledge graph of a specific pod, allows storing and retrieving of structured data."
        ),
        Tag(
            name = ApiDocTags.STORAGE_API,
            description = "API for interfacing with low-level storage for a specific pod via S3 operations, modified to be compatible with Solid auth(n/z). For more information, see the [S3 API documentation](https://docs.aws.amazon.com/AmazonS3/latest/API/API_Operations.html)."
        ),
    ]
)
class KvasirApplication : Application() {
}