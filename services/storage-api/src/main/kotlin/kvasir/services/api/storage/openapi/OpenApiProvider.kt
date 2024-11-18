package kvasir.services.api.storage.openapi

import io.quarkus.smallrye.openapi.OpenApiFilter
import io.smallrye.openapi.api.models.OperationImpl
import io.smallrye.openapi.api.models.PathItemImpl
import io.smallrye.openapi.api.models.PathsImpl
import io.smallrye.openapi.api.models.media.ContentImpl
import io.smallrye.openapi.api.models.media.MediaTypeImpl
import io.smallrye.openapi.api.models.media.SchemaImpl
import io.smallrye.openapi.api.models.parameters.ParameterImpl
import io.smallrye.openapi.api.models.parameters.RequestBodyImpl
import io.smallrye.openapi.api.models.responses.APIResponseImpl
import io.smallrye.openapi.api.models.responses.APIResponsesImpl
import kvasir.definitions.openapi.ApiDocTags
import kvasir.services.api.storage.HEADER_X_AMZ_CONTENT_SHA256
import kvasir.services.api.storage.HEADER_X_AMZ_DATE
import org.eclipse.microprofile.openapi.OASFilter
import org.eclipse.microprofile.openapi.models.OpenAPI
import org.eclipse.microprofile.openapi.models.PathItem
import org.eclipse.microprofile.openapi.models.media.Schema
import org.eclipse.microprofile.openapi.models.parameters.Parameter

@OpenApiFilter(OpenApiFilter.RunStage.BUILD)
class OpenApiProvider : OASFilter {

    private val commonParams = listOf(
        ParameterImpl()
            .`in`(Parameter.In.PATH)
            .name("podId")
            .required(true)
            .schema(SchemaImpl().type(Schema.SchemaType.STRING)),
        ParameterImpl()
            .`in`(Parameter.In.PATH)
            .name("objectKey")
            .required(true)
            .schema(SchemaImpl().type(Schema.SchemaType.STRING))
    )

    override fun filterOpenAPI(openAPI: OpenAPI) {
        if (openAPI.paths == null) {
            openAPI.paths = PathsImpl()
        }
        generateS3Operations(openAPI, "/{podId}/s3/{objectKey}", "the specified pod's S3 storage")
        generateS3Operations(openAPI, "/{podId}/slices/{sliceId}/s3/{objectKey}", "the specified slice's S3 storage")
    }

    private fun generateS3Operations(
        openAPI: OpenAPI,
        path: String,
        targetRefDescription: String
    ) {
        openAPI.paths.addPathItem(path, PathItemImpl().apply {
            this.setOperation(PathItem.HttpMethod.GET, OperationImpl().apply {
                this.summary = "Download a stored object."
                this.description = "Get an object from $targetRefDescription."
                this.addTag(ApiDocTags.STORAGE_API)
                this.parameters = commonParams
                this.responses = APIResponsesImpl().addAPIResponse("200", APIResponseImpl().apply {
                    this.description = "The object content"
                })
            })
            this.setOperation(PathItem.HttpMethod.PUT, OperationImpl().apply {
                this.summary = "Upload an object."
                this.description = "Put an object to $targetRefDescription."
                this.addTag(ApiDocTags.STORAGE_API)
                this.parameters = commonParams.plus(
                    listOf(
                        ParameterImpl()
                            .`in`(Parameter.In.HEADER)
                            .name(HEADER_X_AMZ_CONTENT_SHA256)
                            .description("The SHA256 hash of the object content (allows the server to verify the integrity of the content).")
                            .required(true)
                            .schema(SchemaImpl().type(Schema.SchemaType.STRING)),
                        ParameterImpl()
                            .`in`(Parameter.In.HEADER)
                            .name("Content-Type")
                            .description("The MIME type of the object content.")
                            .required(true)
                            .schema(SchemaImpl().type(Schema.SchemaType.STRING)),
                        ParameterImpl()
                            .`in`(Parameter.In.HEADER)
                            .name(HEADER_X_AMZ_DATE)
                            .description("The date and time of the request, in ISO8601 format (offers additional protection against replay attacks).")
                            .required(false)
                            .schema(
                                SchemaImpl().type(Schema.SchemaType.STRING)
                            )
                    )
                )
                this.requestBody(RequestBodyImpl().apply {
                    this.required(true)
                    this.content = ContentImpl().addMediaType("*/*", MediaTypeImpl().apply {
                        this.schema = SchemaImpl().type(Schema.SchemaType.STRING)
                    })
                })
                this.responses = APIResponsesImpl().addAPIResponse("200", APIResponseImpl().apply {
                    this.description = "The object was stored successfully"
                })
            })
            this.setOperation(PathItem.HttpMethod.DELETE, OperationImpl().apply {
                this.summary = "Remove a stored object."
                this.description = "Delete an object from $targetRefDescription."
                this.addTag(ApiDocTags.STORAGE_API)
                this.parameters = commonParams
                this.responses = APIResponsesImpl().addAPIResponse("204", APIResponseImpl().apply {
                    this.description = "The object was deleted successfully"
                })
            })
        })
    }

}