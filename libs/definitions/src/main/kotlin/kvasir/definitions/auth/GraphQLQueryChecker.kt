package kvasir.definitions.auth

import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni

/**
 * Checks whether a given GraphQL query is allowed for a given security identity.
 * Allows implementing classes to enforce fine-grained access control on GraphQL queries, for security mechanisms
 * that otherwise don't have access to the query itself (e.g. A4DS).
 */
interface GraphQLQueryChecker {

    fun checkAccess(
        endpointUri: String,
        securityIdentity: SecurityIdentity,
        query: String,
        operationName: String?
    ): Uni<Void>

}