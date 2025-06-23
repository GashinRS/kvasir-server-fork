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
            name = ApiDocTags.KG_CHANGES_API,
            description = "API for managing changes to a specific Pod's Knowledge Graph (KG), including requesting changes, listing changes, and retrieving change reports."
        ),
        Tag(
            name = ApiDocTags.KG_QUERYING_API,
            description = "API for querying the knowledge graph of a specific Pod using GraphQL."
        ),
        Tag(
            name = ApiDocTags.KG_EVENTS_API,
            description = "API for streaming committed changes to the knowledge graph, query events, life-cycle events and storage (S3) events for a specific Pod."
        ),
        Tag(
            name = ApiDocTags.STORAGE_API,
            description = "API for interfacing with low-level storage (large RDF files, unstructured or binary data) for a specific pod via S3 operations, modified to be compatible with Solid auth(n/z). For more information, see the [S3 API documentation](https://docs.aws.amazon.com/AmazonS3/latest/API/API_Operations.html)."
        ),
        Tag(
            name = ApiDocTags.PODS_API,
            description = "API for managing Pods, including creating, listing, deleting pods, managing slices, etc."
        )
    ]
)
class KvasirApplication : Application() {
}