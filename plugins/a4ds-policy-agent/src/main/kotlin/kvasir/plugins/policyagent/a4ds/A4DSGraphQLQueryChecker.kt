package kvasir.plugins.policyagent.a4ds

import graphql.language.OperationDefinition
import graphql.parser.InvalidSyntaxException
import graphql.parser.Parser
import io.quarkus.security.UnauthorizedException
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.auth.GraphQLQueryChecker
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.getJsonArray
import kotlin.jvm.optionals.getOrNull

@ApplicationScoped
class A4DSGraphQLQueryChecker : GraphQLQueryChecker {
    override fun checkAccess(
        endpointUri: String,
        securityIdentity: SecurityIdentity,
        query: String,
        operationName: String?
    ): Uni<Void> {
        val permissions = securityIdentity.getAttribute<List<JSONObject>>("permissions")
        val scopes = extractScopeForGraphQLRequest(query, operationName)
        if (permissions.any { permission ->
                permission["resource_id"] == endpointUri && scopes.all { scope ->
                    permission.getJsonArray<String>(
                        "resource_scopes"
                    )?.contains(scope.iri) == true
                }
            }) {
            return Uni.createFrom().voidItem()
        } else {
            return Uni.createFrom().failure(
                UnauthorizedException("Access to GraphQL endpoint '$endpointUri' with scopes ${scopes.map { it.iri }} is not allowed.")
            )
        }
    }


}

internal fun extractScopeForGraphQLRequest(query: String, operationName: String?): Set<Scope> {
    try {
        val graphqlRequest = Parser.parse(query)
        val operations = graphqlRequest.definitions.filterIsInstance<OperationDefinition>().map { it.operation }
        return when {
            operationName != null -> graphqlRequest.getOperationDefinition(operationName)?.getOrNull()
                ?.let { setOf(mapOperationType(it.operation)) }
                ?: throw IllegalArgumentException("GraphQL request does not contain an operation with name '$operationName'.")

            operations.isEmpty() -> throw IllegalArgumentException("GraphQL request does not contain any operations.")

            else -> operations.map { mapOperationType(it) }.toSet()
        }
    } catch (e: InvalidSyntaxException) {
        throw IllegalArgumentException(e)
    }
}

private fun mapOperationType(
    operation: OperationDefinition.Operation
): Scope {
    return when (operation) {
        OperationDefinition.Operation.QUERY, OperationDefinition.Operation.SUBSCRIPTION -> Scope.READ
        OperationDefinition.Operation.MUTATION -> Scope.WRITE
    }
}