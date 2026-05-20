package kvasir.utils.graphql

import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.visibility.GraphqlFieldVisibility
import kvasir.definitions.kg.graphql.DIRECTIVE_HIDDEN_NAME

/**
 * A [GraphqlFieldVisibility] implementation that hides fields annotated with [@hidden][DIRECTIVE_HIDDEN_NAME]
 * from GraphQL introspection and prevents clients from selecting them directly.
 *
 * Hidden fields are still accessible server-side (via [graphql.schema.GraphQLObjectType.getChildren])
 * so that [@filter][kvasir.definitions.kg.graphql.DIRECTIVE_FILTER_NAME] and
 * [@mustExist][kvasir.definitions.kg.graphql.DIRECTIVE_MUST_EXIST_NAME] directives on those fields
 * continue to be applied during SQL query generation.
 */
class HiddenFieldVisibility : GraphqlFieldVisibility {

    override fun getFieldDefinitions(fieldsContainer: GraphQLFieldsContainer): List<GraphQLFieldDefinition> {
        return fieldsContainer.fieldDefinitions.filter { it.getAppliedDirective(DIRECTIVE_HIDDEN_NAME) == null }
    }

    override fun getFieldDefinition(fieldsContainer: GraphQLFieldsContainer, fieldName: String): GraphQLFieldDefinition? {
        val fieldDef = fieldsContainer.getFieldDefinition(fieldName) ?: return null
        return if (fieldDef.getAppliedDirective(DIRECTIVE_HIDDEN_NAME) != null) null else fieldDef
    }
}


