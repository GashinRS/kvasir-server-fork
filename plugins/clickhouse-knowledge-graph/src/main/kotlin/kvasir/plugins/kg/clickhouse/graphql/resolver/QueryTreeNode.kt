package kvasir.plugins.kg.clickhouse.graphql.resolver

import cz.jirutka.rsql.parser.ast.Node

/**
 * Represents a node in the query tree, which corresponds to a field in the GraphQL query. Each node is responsible for
 * defining how to project its data from the database, and can optionally define filters, grouping keys, and pagination.
 */
interface QueryTreeNode {
    /**
     * The name for this node as defined in the GraphQL schema
     */
    val name: String

    /**
     * The name to use for this node in the final query result. This is typically the alias defined in the GraphQL query,
     * or if no alias is defined, it defaults to the field name.
     */
    val nameInResult: String

    /**
     * Returns the SQL expressions to include in the SELECT clause of the parent of this node.
     */
    fun buildProjection(): String

    /**
     * Returns whether this node should be used as a grouping key in the parent node. If true, the parent node will include this node in its GROUP BY clause.
     */
    fun isGroupingKey(): Boolean = true

    /**
     * Returns whether this node should be included in the result mapping. If false, this node will not be included in the final result mapping.
     * E.g. this is used for temporary count values.
     */
    fun isIncludeInResultMapping(): Boolean = true

}

/**
 * A node that represents a field which requires joining with another table to fetch its data.
 * It defines how to perform the join and what the join conditions are.
 */
interface JoinableNode : QueryTreeNode {
    /**
     * A unique identifier for the join, which can be used to reference this join in other parts of the query.
     */
    val joinIdentifier: String

    /**
     * Returns a list of SQL join statements to include in the parent that are needed to fetch the data for this node.
     */
    fun getJoinStatements(): List<String>

    /**
     * Returns whether this node requires pagination.
     * (Function for JoinableNode, as only these type of nodes can represent collections that require pagination.)
     */
    fun isPaginated(): Boolean
}

/**
 * A node that references a specific Type table. These tables are not physical tables but are backed by CTEs.
 * By exposing the referenced types, we can determine which CTEs need to be included in the query.
 */
interface NodeWithTypeRefs : QueryTreeNode {
    /**
     * Returns a list of TypeInfo objects representing the types that this node references.
     */
    fun getTypeRefs(): List<TypeInfo>
}

/**
 * A node that references specific Relation tables. These tables are not physical tables but are backed by CTEs.
 * By exposing the referenced relations, we can determine which CTEs need to be included in the query.
 */
interface NodeWithRelationRefs : QueryTreeNode {
    /**
     * Returns a list of RelationInfo objects representing the relations that this node references.
     */
    fun getRelationRefs(): List<RelationInfo>
}

/**
 * A node that can have an RSQL filter applied to it, but the filter should be applied in the context of the parent node.
 * E.g. for filter directives on scalar fields.
 */
interface NodeWithFilterForParent : QueryTreeNode {

    /**
     * Returns an RSQL AST representing the filter to apply to this node. Allows passing along the filter to the parent, were it can be processed.
     */
    fun getNodeFilter(): Node?

    /**
     * Returns an RSQL AST representing a filter derived from a field argument.
     */
    fun getArgFilter(): Node? = null
}