package kvasir.services.api.kg.query

import com.fasterxml.jackson.annotation.JsonProperty
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.definitions.kg.PodStore
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.kg.QueryResult
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.kg.slices.SliceStore
import kvasir.definitions.kg.slices.SliceSummary
import kvasir.definitions.openapi.ApiDocConstants
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.rdf.JSON_LD_MEDIA_TYPE
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import kvasir.utils.graphql.SchemaValidator
import kvasir.utils.shacl.GraphQL2SHACL
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.net.URI

@Path("")
class GraphSlicesApi(
    private val sliceStore: SliceStore,
    private val podStore: PodStore,
    private val knowledgeGraph: KnowledgeGraph,
    private val uriInfo: UriInfo
) {

    @Tag(name = ApiDocTags.PODS_API)
    @Path("{podId}/slices")
    @GET
    @Produces(JSON_LD_MEDIA_TYPE)
    @Operation(
        summary = "List slices of the specified pod.",
        description = "List slices of the specified pod's Knowledge Graph."
    )
    fun listSlices(@PathParam("podId") podId: String): Uni<List<SliceSummary>> {
        val fqPodId = uriInfo.absolutePath.toString().substringBefore("/slices")
        return getPodOrThrow404(podStore, fqPodId).chain { _ ->
            sliceStore.list(fqPodId)
        }
    }

    @Tag(name = ApiDocTags.PODS_API)
    @Path("{podId}/slices")
    @POST
    @Consumes(JSON_LD_MEDIA_TYPE)
    @Operation(
        summary = "Define a new slice of the KG.",
        description = "Define a new slice (subset) of the specified pod's Knowledge Graph, based on a GraphQL-LD schema."
    )
    fun createSlice(@PathParam("podId") podId: String, input: SliceInput): Uni<Response> {
        val fqPodId = uriInfo.absolutePath.toString().substringBefore("/slices")
        val fqSliceId = uriInfo.absolutePathBuilder.path(input.name).build().toString()
        return getPodOrThrow404(podStore, fqPodId)
            .chain { _ ->
                // A Slice with the same name should not exist
                sliceStore.getById(fqPodId, fqSliceId)
                    .onItem().ifNotNull().failWith(ClientErrorException(Response.Status.CONFLICT))
                    .onItem().ifNull().switchTo { validateAndPersistSlice(fqPodId, fqSliceId, input) }
            }
            .map { slice ->
                Response.created(URI.create(slice!!.id)).build()
            }
    }

    @Tag(name = ApiDocTags.PODS_API)
    @Path("{podId}/slices/{sliceId}")
    @GET
    @Produces(JSON_LD_MEDIA_TYPE)
    @Operation(
        summary = "Retrieve a specific slice definition..",
        description = "Retrieve a specific slice definition details."
    )
    fun getSlice(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String
    ): Uni<Slice> {
        val fqPodId = uriInfo.absolutePath.toString().substringBefore("/slices")
        val fqSliceId = uriInfo.absolutePath.toString()
        return getSliceOrThrow404(sliceStore, fqPodId, fqSliceId)
    }

    @Tag(name = ApiDocTags.PODS_API)
    @Path("{podId}/slices/{sliceId}")
    @PUT
    @Consumes(JSON_LD_MEDIA_TYPE)
    @Operation(
        summary = "Update a specific slice definition..",
        description = "Update a specific slice definition details."
    )
    fun updateSlice(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        input: SliceInput,
    ): Uni<Response> {
        val fqPodId = uriInfo.absolutePath.toString().substringBefore("/slices")
        val fqSliceId = uriInfo.absolutePath.toString()
        return getSliceOrThrow404(sliceStore, fqPodId, fqSliceId).chain { _ ->
            validateAndPersistSlice(fqPodId, fqSliceId, input).map { _ -> Response.noContent().build() }
        }
    }

    @Tag(name = ApiDocTags.PODS_API)
    @Path("{podId}/slices/{sliceId}")
    @DELETE
    @Operation(
        summary = "Delete a specific slice.",
        description = "Delete a specific slice of the specified pod's Knowledge Graph."
    )
    fun deleteSlice(@PathParam("podId") podId: String, @PathParam("sliceId") sliceId: String): Uni<Response> {
        val fqPodId = uriInfo.absolutePath.toString().substringBefore("/slices")
        val fqSliceId = uriInfo.absolutePath.toString()
        return getSliceOrThrow404(sliceStore, fqPodId, fqSliceId).chain { _ ->
            sliceStore.deleteById(fqPodId, fqSliceId)
                .map { Response.noContent().build() }
        }
    }

    @Tag(name = ApiDocTags.KG_QUERYING_API)
    @POST
    @Path("{podId}/slices/{sliceId}/query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Retrieve data from a specific subset of the KG.",
        description = "Query a predefined slice of the specified pod's Knowledge Graph using GraphQL."
    )
    fun queryVirtual(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") @Parameter(description = "Identifier of the Knowledge Graph slice, representing a subset of the specified pod's Knowledge Graph.") sliceId: String,
        input: QueryInputImpl,
    ): Uni<QueryResult> {
        val fqPodId = uriInfo.absolutePath.toString().substringBefore("/slices")
        val fqSliceId = uriInfo.absolutePath.toString().substringBefore("/query")
        return getSliceOrThrow404(sliceStore, fqPodId, fqSliceId).chain { slice ->
            executeQuery(fqPodId, slice, input)
        }
    }

    @Tag(name = ApiDocTags.KG_QUERYING_API)
    @POST
    @Path("{podId}/slices/{sliceId}/query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(JSON_LD_MEDIA_TYPE)
    @APIResponse(
        responseCode = "200",
        description = "The query result in JSON-LD format.",
        content = [Content(example = ApiDocConstants.JSON_LD_RESPONSE_EXAMPLE)]
    )
    fun queryVirtualJsonLD(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        input: QueryInputImpl,
    ): Uni<Any> {
        val fqPodId = uriInfo.absolutePath.toString().substringBefore("/slices")
        val fqSliceId = uriInfo.absolutePath.toString().substringBefore("/query")
        return getSliceOrThrow404(sliceStore, fqPodId, fqSliceId).chain { slice ->
            executeQuery(fqPodId, slice, input).map {
                it.toJsonLD(slice.context)
            }
        }
    }

    private fun executeQuery(
        podId: String,
        slice: Slice,
        input: QueryInputImpl
    ): Uni<QueryResult> {
        // Execute the query
        return knowledgeGraph.query(
            QueryRequest(
                slice.context,
                podId,
                slice.id,
                input.query,
                input.variables,
                input.operationName,
                slice.targetGraphs,
                slice.schema,
                input.atTimestamp,
                input.atChangeRequest
            )
        )
    }

    private fun validateAndPersistSlice(podId: String, sliceId: String, input: SliceInput): Uni<Slice> {
        return try {
            // Generate shapes
            val shaclConvertor = GraphQL2SHACL(input.schema, input.context)
            val slice = input.toSlice(podId, sliceId, shaclConvertor.hasMutations())
            // Validate the schema
            SchemaValidator.validateSchema(slice.schema, slice.context)
            sliceStore.persist(slice).map { slice }
        } catch (err: Throwable) {
            Uni.createFrom().failure(err)
        }
    }
}

data class SliceInput(
    @JsonProperty(JsonLdKeywords.context)
    val context: Map<String, Any>,
    @JsonProperty(KvasirVocab.name)
    val name: String,
    @JsonProperty(KvasirVocab.schema)
    val schema: String,
    @JsonProperty(KvasirVocab.description)
    val description: String = "",
    @JsonProperty(KvasirVocab.targetGraphs)
    val targetGraphs: Set<String> = emptySet()
) {
    fun toSlice(podId: String, sliceId: String, supportsChanges: Boolean): Slice {
        return Slice(
            id = sliceId,
            context = context,
            podId = podId,
            name = name,
            description = description,
            schema = schema,
            supportsChanges = supportsChanges,
            targetGraphs = targetGraphs
        )
    }
}