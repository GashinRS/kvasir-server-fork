package kvasir.services.api.kg.query

import com.fasterxml.jackson.annotation.JsonProperty
import idlab.quarkus.ext.pep.openfga.model.annotations.OpenFgaPolicyEnforcer
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.auth.AuthConstants
import kvasir.definitions.config.PodConfig
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.kg.QueryResult
import kvasir.definitions.openapi.ApiDocConstants
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.plugins.http.common.extensions.openfga.extractors.GraphQLPostRelationExtractor
import kvasir.utils.http.KvasirUriInfo
import kvasir.utils.http.getParentUri
import kvasir.utils.pod.PodConfigProvider
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.media.Schema
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.Instant

const val QUERY_API_PATH = "/query"

@Tag(name = ApiDocTags.KG_QUERYING_API)
@Path("")
class QueryApi(
    private val knowledgeGraph: KnowledgeGraph,
    private val podConfigProvider: PodConfigProvider,
    private val uriInfo: KvasirUriInfo,
    private val securityIdentity: Instance<SecurityIdentity>
) {

    @Path("{podId}$QUERY_API_PATH")
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Retrieve data from the KG.",
        description = "Query the knowledge graph of the specified pod using GraphQL."
    )
    @OpenFgaPolicyEnforcer(relation = GraphQLPostRelationExtractor::class, readBody = true)
    fun query(
        @PathParam("podId") podId: String,
        input: QueryInputWithContext
    ): Uni<QueryResult> {
        val fqPodId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return podConfigProvider.getPodConfigById(fqPodId).onItem().ifNull()
            .failWith(NotFoundException("Pod not found: $podId"))
            .onItem().ifNotNull().transformToUni { podConfig ->
                val req = parseInput(fqPodId, podConfig!!, input)
                knowledgeGraph.query(req).toUni()
            }
    }

    @Path("{podId}$QUERY_API_PATH")
    @POST
    @Produces(RDFMediaTypes.JSON_LD)
    @APIResponse(
        responseCode = "200",
        content = [Content(example = ApiDocConstants.JSON_LD_RESPONSE_EXAMPLE)]
    )
    @OpenFgaPolicyEnforcer(relation = GraphQLPostRelationExtractor::class, readBody = true)
    fun queryJsonLD(
        @PathParam("podId") podId: String, input: QueryInputWithContext
    ): Uni<Any> {
        val fqPodId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return podConfigProvider.getPodConfigById(fqPodId).onItem().ifNull()
            .failWith(NotFoundException("Pod not found: $podId"))
            .onItem().ifNotNull().transformToUni { podConfig ->
                val req = parseInput(fqPodId, podConfig!!, input)
                knowledgeGraph.query(req).map {
                    it.toJsonLD(req.context)
                }.toUni()
            }
    }

    private fun parseInput(
        podId: String,
        podConfig: PodConfig,
        input: QueryInputWithContext
    ): QueryRequest {
        return QueryRequest(
            context = input.providedContext ?: podConfig.defaultContext(),
            requestingUser = securityIdentity.takeIf { it.isResolvable }?.get()?.principal?.name ?: AuthConstants.ANONYMOUS_USERNAME,
            podId = podId,
            query = input.query,
            variables = input.variables,
            operationName = input.operationName,
            atTimestamp = input.atTimestamp,
            atChangeId = input.atChangeId?.substringAfterLast("/")
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
    val atChangeId: String?
}

data class QueryInputImpl(
    override val query: String,
    override val operationName: String? = null,
    override val variables: Map<String, Any>? = null,
    override val atTimestamp: Instant? = null,
    override val atChangeId: String? = null
) : QueryInput

data class QueryInputWithContext(
    override val query: String,
    override val operationName: String? = null,
    override val variables: Map<String, Any>? = null,
    override val atTimestamp: Instant? = null,
    override val atChangeId: String? = null,
    @get:JsonProperty("@context")
    @get:Schema(
        name = "@context",
        description = "The JSON-LD context for the query.",
        example = ApiDocConstants.JSON_LD_CONTEXT_EXAMPLE
    )
    val providedContext: Map<String, Any>? = null
) : QueryInput
