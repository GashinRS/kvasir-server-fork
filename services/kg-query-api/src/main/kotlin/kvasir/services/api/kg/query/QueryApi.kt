package kvasir.services.api.kg.query

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import graphql.language.*
import graphql.parser.Parser
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import graphql.util.TreeTransformerUtil
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.definitions.kg.QueryRequest

@Path("{podId}/kg/query")
class QueryApi(
    private val knowledgeGraph: KnowledgeGraph
) {

    @POST
    @Produces("application/json")
    fun query(@PathParam("podId") podId: String, input: QueryInput): Uni<ContextualizedQueryResult> {
        val req = parseInput(podId, input)
        return knowledgeGraph.query(req).map { ContextualizedQueryResult(it.data, input.providedContext) }
    }

    private fun parseInput(podId: String, input: QueryInput): QueryRequest {
        val queryDoc = Parser.parse(input.query)
        val contextualizedDoc = AstTransformer().transform(queryDoc, ContextualizingQueryVisitor(input.providedContext ?: emptyMap()))
        return QueryRequest(
            podId,
            contextualizedDoc as Document,
            input.variables,
            input.operationName
        )
    }

}

data class QueryInput(
    @JsonProperty("@context")
    val providedContext: Map<String, Any>? = null,
    val query: String,
    val operationName: String? = null,
    val variables: Map<String, Any>? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ContextualizedQueryResult(
    val data: Collection<Any>,
    @JsonProperty("@context")
    val context: Map<String, Any>? = null
)

class ContextualizingQueryVisitor(private val providedContext: Map<String, Any>) : NodeVisitorStub() {

    override fun visitField(node: Field, traverserContext: TraverserContext<Node<*>>): TraversalControl {
        val changedField = node.transform {
            providedContext[node.name]?.let { iri ->
                it.directive(
                    Directive.newDirective().name("context")
                        .argument(
                            Argument.newArgument(
                                "iri",
                                StringValue.of(iri as String)
                            ).build()
                        )
                        .build()
                )
            }
        }
        return TreeTransformerUtil.changeNode(traverserContext, changedField)
    }
}