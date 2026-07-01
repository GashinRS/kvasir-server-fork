package kvasir.services.api.kg.query

import graphql.parser.Parser
import idlab.quarkus.ext.pep.openfga.model.annotations.OpenFgaPolicyEnforcer
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.vertx.core.json.JsonObject
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.sse.OutboundSseEvent
import jakarta.ws.rs.sse.Sse
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.kg.QueryResult
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.kg.slices.tryReadingEmbeddedSDL
import kvasir.definitions.openapi.ApiDocConstants
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.persistence.VersionedRepository
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.plugins.http.common.extensions.openfga.extractors.GraphQLGetRelationExtractor
import kvasir.plugins.http.common.extensions.openfga.extractors.GraphQLPostRelationExtractor
import kvasir.utils.http.KvasirUriInfo
import kvasir.utils.http.getParentUri
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.jboss.resteasy.reactive.RestStreamElementType
import java.util.*
import kotlin.jvm.optionals.getOrNull

@Path("")
class GraphSlicesApi(
    private val knowledgeGraph: KnowledgeGraph,
    private val uriInfo: KvasirUriInfo,
    private val sse: Sse,
    private val repositoryFactory: RepositoryFactory,
    private val securityIdentity: Instance<SecurityIdentity>,
    @ConfigProperty(name = "kvasir.auth.anonymous-user-name", defaultValue = "anonymous")
    private val anonymousUserName: String
) {

    @Tag(name = ApiDocTags.KG_QUERYING_API)
    @POST
    @Path("{podId}/slices/{sliceId}/query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Interact with a specific subset of the KG.",
        description = "Execute a query on a predefined slice of the specified pod's Knowledge Graph using GraphQL."
    )
    @OpenFgaPolicyEnforcer(relation = GraphQLPostRelationExtractor::class, readBody = true)
    fun queryVirtual(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") @Parameter(description = "Identifier of the Knowledge Graph slice, representing a subset of the specified pod's Knowledge Graph.") sliceId: String,
        input: QueryInputImpl,
    ): Uni<QueryResult> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(3).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return getSliceOrThrow404(
            repositoryFactory.getVersionedRepository(Slice::class, fqPodId),
            fqPodId,
            fqSliceId
        ).chain { slice ->
            executeQuery(fqPodId, slice, input).toUni()
        }
    }

    @POST
    @Path("{podId}/slices/{sliceId}/query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @OpenFgaPolicyEnforcer(relation = GraphQLPostRelationExtractor::class, readBody = true)
    fun streamVirtual(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") @Parameter(description = "Identifier of the Knowledge Graph slice, representing a subset of the specified pod's Knowledge Graph.") sliceId: String,
        input: QueryInputImpl,
    ): Multi<OutboundSseEvent> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(3).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return getSliceOrThrow404(
            repositoryFactory.getVersionedRepository(Slice::class, fqPodId),
            fqPodId,
            fqSliceId
        ).onItem()
            .transformToMulti { slice ->
                executeQuery(fqPodId, slice, input).map { sse.newEventBuilder().name("next").data(it).build() }
            }
    }

    /**
     * A GET variant of the query endpoint for Subscriptions is provided for compatibility with SSE clients that
     * only support GET requests.
     */
    @GET
    @Path("{podId}/slices/{sliceId}/query")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @OpenFgaPolicyEnforcer(relation = GraphQLGetRelationExtractor::class)
    fun streamVirtualViaGet(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        @QueryParam("query") query: String,
        @QueryParam("variables") variables: Optional<String>,
        @QueryParam("operationName") operationName: Optional<String>,
    ): Multi<OutboundSseEvent> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(3).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        val queryInputImpl =
            QueryInputImpl(
                query = query,
                variables = variables.getOrNull()?.let { JsonObject(it).map },
                operationName = operationName.getOrNull()
            )
        return getSliceOrThrow404(
            repositoryFactory.getVersionedRepository(Slice::class, fqPodId),
            fqPodId,
            fqSliceId
        ).onItem()
            .transformToMulti { slice ->
                executeQuery(fqPodId, slice, queryInputImpl).map {
                    sse.newEventBuilder().name("next").data(it).build()
                }
            }
    }

    @POST
    @Path("{podId}/slices/{sliceId}/query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(RDFMediaTypes.JSON_LD)
    @APIResponse(
        responseCode = "200",
        content = [Content(example = ApiDocConstants.JSON_LD_RESPONSE_EXAMPLE)]
    )
    @OpenFgaPolicyEnforcer(relation = GraphQLPostRelationExtractor::class, readBody = true)
    fun queryVirtualJsonLD(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        input: QueryInputImpl,
    ): Uni<Any> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(3).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return getSliceOrThrow404(
            repositoryFactory.getVersionedRepository(
                Slice::class,
                fqPodId
            ), fqPodId, fqSliceId
        ).chain { slice ->
            executeQuery(fqPodId, slice, input).toUni().map {
                it.toJsonLD(slice.context, slice.schema.tryReadingEmbeddedSDL())
            }
        }
    }

    @Tag(name = ApiDocTags.KG_QUERYING_API)
    @POST
    @Path("{podId}/slices/{sliceId}/tags/{tag}/query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Interact with a specific tagged version of a KG slice.",
        description = "Execute a query on a predefined slice of the specified pod's Knowledge Graph at the version associated with the given tag."
    )
    @OpenFgaPolicyEnforcer(relation = GraphQLPostRelationExtractor::class, readBody = true)
    fun queryVirtualTagged(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") @Parameter(description = "Identifier of the Knowledge Graph slice.") sliceId: String,
        @PathParam("tag") @Parameter(description = "Tag identifying the specific version of the slice to query.") tag: String,
        input: QueryInputImpl,
    ): Uni<QueryResult> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(5).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri(3).toASCIIString()
        return getSliceOrThrow404(
            repositoryFactory.getVersionedRepository(Slice::class, fqPodId),
            fqPodId,
            fqSliceId,
            tag
        ).chain { slice ->
            executeQuery(fqPodId, slice, input).toUni()
        }
    }

    @POST
    @Path("{podId}/slices/{sliceId}/tags/{tag}/query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @OpenFgaPolicyEnforcer(relation = GraphQLPostRelationExtractor::class, readBody = true)
    fun streamVirtualTagged(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") @Parameter(description = "Identifier of the Knowledge Graph slice.") sliceId: String,
        @PathParam("tag") @Parameter(description = "Tag identifying the specific version of the slice to query.") tag: String,
        input: QueryInputImpl,
    ): Multi<OutboundSseEvent> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(5).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri(3).toASCIIString()
        return getSliceOrThrow404(
            repositoryFactory.getVersionedRepository(Slice::class, fqPodId),
            fqPodId,
            fqSliceId,
            tag
        ).onItem()
            .transformToMulti { slice ->
                executeQuery(fqPodId, slice, input).map { sse.newEventBuilder().name("next").data(it).build() }
            }
    }

    /**
     * A GET variant of the tagged query endpoint for Subscriptions is provided for compatibility with SSE clients that
     * only support GET requests.
     */
    @GET
    @Path("{podId}/slices/{sliceId}/tags/{tag}/query")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @OpenFgaPolicyEnforcer(relation = GraphQLGetRelationExtractor::class)
    fun streamVirtualViaGetTagged(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        @PathParam("tag") tag: String,
        @QueryParam("query") query: String,
        @QueryParam("variables") variables: Optional<String>,
        @QueryParam("operationName") operationName: Optional<String>,
    ): Multi<OutboundSseEvent> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(5).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri(3).toASCIIString()
        val queryInputImpl =
            QueryInputImpl(
                query = query,
                variables = variables.getOrNull()?.let { JsonObject(it).map },
                operationName = operationName.getOrNull()
            )
        return getSliceOrThrow404(
            repositoryFactory.getVersionedRepository(Slice::class, fqPodId),
            fqPodId,
            fqSliceId,
            tag
        ).onItem()
            .transformToMulti { slice ->
                executeQuery(fqPodId, slice, queryInputImpl).map {
                    sse.newEventBuilder().name("next").data(it).build()
                }
            }
    }

    @POST
    @Path("{podId}/slices/{sliceId}/tags/{tag}/query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(RDFMediaTypes.JSON_LD)
    @APIResponse(
        responseCode = "200",
        content = [Content(example = ApiDocConstants.JSON_LD_RESPONSE_EXAMPLE)]
    )
    @OpenFgaPolicyEnforcer(relation = GraphQLPostRelationExtractor::class, readBody = true)
    fun queryVirtualJsonLDTagged(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        @PathParam("tag") tag: String,
        input: QueryInputImpl,
    ): Uni<Any> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(5).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri(3).toASCIIString()
        return getSliceOrThrow404(
            repositoryFactory.getVersionedRepository(Slice::class, fqPodId),
            fqPodId,
            fqSliceId,
            tag
        ).chain { slice ->
            executeQuery(fqPodId, slice, input).toUni().map {
                it.toJsonLD(slice.context, slice.schema.tryReadingEmbeddedSDL())
            }
        }
    }

    private fun executeQuery(
        podId: String,
        slice: Slice,
        input: QueryInputImpl,
        tag: String? = null
    ): Multi<QueryResult> {
        // Parse query document
        val queryDoc = Parser.parse(input.query)

        // Execute the query
        return knowledgeGraph.query(
            QueryRequest(
                context = slice.context,
                securityIdentity.takeIf { it.isResolvable }?.get()?.principal?.name ?: anonymousUserName,
                podId = podId,
                sliceId = slice.id,
                sliceTag = tag,
                query = input.query,
                variables = input.variables,
                operationName = input.operationName,
                predefinedSchema = slice.schema.tryReadingEmbeddedSDL(),
                atTimestamp = input.atTimestamp,
                // Strip URI prefix from change ID if present
                atChangeId = input.atChangeId?.substringAfterLast("/")
            )
        )
    }
}

internal fun getSliceOrThrow404(
    sliceStore: VersionedRepository<Slice>,
    podId: String,
    sliceId: String,
    tag: String? = null
): Uni<Slice> {
    return sliceStore.run {
        // When a tag is specified, use the matching revision. Else: use the default tag (or the latest revision on main if no such tag exists).
        tag?.let { this.findById(sliceId, it) } ?: this.findDefaultForId(sliceId)
    }
        .onItem().ifNull().failWith(
            NotFoundException(if (tag != null) "Slice tag not found: $tag" else "Slice not found: $sliceId")
        )
        .onItem().ifNotNull().transform { it!! }
}