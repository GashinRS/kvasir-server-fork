package kvasir.services.api.kg.query

import com.fasterxml.jackson.annotation.JsonProperty
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import io.vertx.core.json.JsonObject
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.kg.*
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.kg.slices.SliceStore
import kvasir.definitions.openapi.ApiDocConstants
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.rdf.JSON_LD_MEDIA_TYPE
import kvasir.utils.http.KvasirUriInfo
import kvasir.utils.http.getParentUri
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.media.Schema
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.Instant
import java.util.*
import kotlin.jvm.optionals.getOrNull

const val QUERY_API_PATH = "/query"

@Tag(name = ApiDocTags.KG_QUERYING_API)
@Path("")
class QueryApi(
    private val knowledgeGraph: KnowledgeGraph,
    private val podStore: PodStore,
    private val uriInfo: KvasirUriInfo
) {

    @Path("{podId}$QUERY_API_PATH")
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Retrieve data from the KG.",
        description = "Query the knowledge graph of the specified pod using GraphQL."
    )
    fun query(
        @PathParam("podId") podId: String,
        input: QueryInputWithContext
    ): Uni<QueryResult> {
        val fqPodId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return podStore.getById(fqPodId).onItem().ifNull().failWith(NotFoundException("Pod not found: $podId"))
            .onItem().ifNotNull().transformToUni { pod ->
                val req = parseInput(pod!!, input)
                knowledgeGraph.query(req).toUni()
            }
    }

    @Path("{podId}$QUERY_API_PATH")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Retrieve data from the KG.",
        description = "Query the knowledge graph of the specified pod using GraphQL."
    )
    fun queryViaGet(
        @PathParam("podId") podId: String,
        @QueryParam("query") query: String,
        @QueryParam("variables") variables: Optional<String>,
        @QueryParam("operationName") operationName: Optional<String>,
        @QueryParam("atTimestamp") atTimestamp: Optional<Instant>,
        @QueryParam("atChangeRequest") atChangeRequest: Optional<String>
    ): Uni<QueryResult> {
        val fqPodId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return podStore.getById(fqPodId).onItem().ifNull().failWith(NotFoundException("Pod not found: $podId"))
            .onItem().ifNotNull().transformToUni { pod ->
                val req = parseInput(
                    pod!!,
                    QueryInputWithContext(
                        query,
                        operationName.getOrNull(),
                        variables.getOrNull()?.let { JsonObject(it).map },
                        atTimestamp.getOrNull(),
                        atChangeRequest.getOrNull()
                    )
                )
                knowledgeGraph.query(req).toUni()
            }
    }

    @Path("{podId}$QUERY_API_PATH")
    @POST
    @Produces(JSON_LD_MEDIA_TYPE)
    @APIResponse(
        responseCode = "200",
        content = [Content(example = ApiDocConstants.JSON_LD_RESPONSE_EXAMPLE)]
    )
    fun queryJsonLD(
        @PathParam("podId") podId: String, input: QueryInputWithContext
    ): Uni<Any> {
        val fqPodId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return podStore.getById(fqPodId).onItem().ifNull().failWith(NotFoundException("Pod not found: $podId"))
            .onItem().ifNotNull().transformToUni { pod ->
                val req = parseInput(pod!!, input)
                knowledgeGraph.query(req).map {
                    it.toJsonLD(req.context)
                }.toUni()
            }
    }

    @Path("{podId}$QUERY_API_PATH")
    @GET
    @Produces(JSON_LD_MEDIA_TYPE)
    @APIResponse(
        responseCode = "200",
        content = [Content(example = ApiDocConstants.JSON_LD_RESPONSE_EXAMPLE)]
    )
    fun queryJsonLDViaGet(
        @PathParam("podId") podId: String,
        @QueryParam("query") query: String,
        @QueryParam("variables") variables: Optional<String>,
        @QueryParam("operationName") operationName: Optional<String>,
        @QueryParam("atTimestamp") atTimestamp: Optional<Instant>,
        @QueryParam("atChangeRequest") atChangeRequest: Optional<String>
    ): Uni<Any> {
        val fqPodId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return podStore.getById(fqPodId).onItem().ifNull().failWith(NotFoundException("Pod not found: $podId"))
            .onItem().ifNotNull().transformToUni { pod ->
                val req = parseInput(
                    pod!!, QueryInputWithContext(
                        query,
                        operationName.getOrNull(),
                        variables.getOrNull()?.let { JsonObject(it).map },
                        atTimestamp.getOrNull(),
                        atChangeRequest.getOrNull()
                    )
                )
                knowledgeGraph.query(req).map {
                    it.toJsonLD(req.context)
                }.toUni()
            }
    }

    private fun parseInput(
        pod: Pod,
        input: QueryInputWithContext
    ): QueryRequest {
        return QueryRequest(
            input.providedContext ?: pod.getDefaultContext(),
            pod.id,
            null,
            input.query,
            input.variables,
            input.operationName,
            atTimestamp = input.atTimestamp,
            atChangeRequestId = input.atChangeRequest
        )
    }
}

interface QueryInput {


    @get:Schema(
        description = "The GraphQL query string to be executed.",
        example = "{ id ex_givenName(_: \"Bob\") ex_friends { id ex_givenName } }"
    )
    val query: String

    @get:Schema(
        description = "The name of the operation to be executed (optional, only required if the GraphQL query expresses more than one operation)."
    )
    val operationName: String?

    @get:Schema(
        description = "The variables to be used in the query."
    )
    val variables: Map<String, Any>?

    @get:Schema(
        description = "Query the state of the KG at the specified point in time."
    )
    val atTimestamp: Instant?

    @get:Schema(
        description = "Query the state of the KG when the specified change request was applied."
    )
    val atChangeRequest: String?
}

data class QueryInputImpl(
    override val query: String,
    override val operationName: String? = null,
    override val variables: Map<String, Any>? = null,
    override val atTimestamp: Instant? = null,
    override val atChangeRequest: String? = null
) : QueryInput

data class QueryInputWithContext(
    override val query: String,
    override val operationName: String? = null,
    override val variables: Map<String, Any>? = null,
    override val atTimestamp: Instant? = null,
    override val atChangeRequest: String? = null,
    @get:JsonProperty("@context")
    @get:Schema(
        name = "@context",
        description = "The JSON-LD context for the query.",
        example = ApiDocConstants.JSON_LD_CONTEXT_EXAMPLE
    )
    val providedContext: Map<String, Any>? = null
) : QueryInput

internal fun getPodOrThrow404(podStore: PodStore, podId: String): Uni<Pod> {
    return podStore.getById(podId)
        .onItem().ifNull().failWith(NotFoundException("Pod not found: $podId"))
        .onItem().ifNotNull().transform { it!! }
}

internal fun getSliceOrThrow404(sliceStore: SliceStore, podId: String, sliceId: String): Uni<Slice> {
    return sliceStore.getById(podId, sliceId)
        .onItem().ifNull().failWith(NotFoundException("Slice not found: $sliceId"))
        .onItem().ifNotNull().transform { it!! }
}
