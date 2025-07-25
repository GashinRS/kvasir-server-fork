package kvasir.plugins.policyagent.openfga.extractors

import graphql.language.OperationDefinition
import graphql.parser.InvalidSyntaxException
import graphql.parser.Parser
import idlab.quarkus.ext.pep.openfga.runtime.extractors.CommonExtractParams
import idlab.quarkus.ext.pep.openfga.runtime.extractors.relation.OpenFgaRelationExtractor
import io.smallrye.mutiny.Uni
import io.vertx.core.json.JsonObject
import kotlin.jvm.optionals.getOrNull

class GraphQLPostRelationExtractor : OpenFgaRelationExtractor {
    fun extractRelation(body: String): Uni<String> {
        val body = JsonObject(body)
        return extractRelation(body.getString("query"), body.getString("operationName"))
    }

    override fun extract(params: CommonExtractParams): Uni<String> {
        return params.body.getOrNull()?.let { extractRelation(it) } ?: Uni.createFrom()
            .failure(IllegalArgumentException("GraphQL request body is required for relation extraction."))
    }
}

class GraphQLGetRelationExtractor : OpenFgaRelationExtractor {
    override fun extract(params: CommonExtractParams): Uni<String> {
        val queryParams = params.uriInfo().queryParameters
        return extractRelation(queryParams.getFirst("query"), queryParams.getFirst("operationName"))
    }

}

private fun extractRelation(query: String, operationName: String?): Uni<String> {
    try {
        val graphqlRequest = Parser.parse(query)
        val operations = graphqlRequest.definitions.filterIsInstance<OperationDefinition>().map { it.operation }
        return when {
            operationName != null -> graphqlRequest.getOperationDefinition(operationName)?.getOrNull()
                ?.let { Uni.createFrom().item(mapOperationType(it.operation)) }
                ?: Uni.createFrom()
                    .failure(IllegalArgumentException("GraphQL request does not contain an operation with name '$operationName'."))

            operations.size > 1 -> Uni.createFrom()
                .failure(IllegalArgumentException("GraphQL request combines multiple operation types, which is not supported."))

            operations.isEmpty() -> Uni.createFrom()
                .failure(IllegalArgumentException("GraphQL request does not contain any operations."))

            else -> Uni.createFrom().item(mapOperationType(operations.first()))
        }
    } catch (e: InvalidSyntaxException) {
        return Uni.createFrom().failure(IllegalArgumentException(e))
    }
}

private fun mapOperationType(
    operation: OperationDefinition.Operation
): String {
    return when (operation) {
        OperationDefinition.Operation.QUERY, OperationDefinition.Operation.SUBSCRIPTION -> "can_read"
        OperationDefinition.Operation.MUTATION -> "can_write"
    }
}