package kvasir.plugins.kg.clickhouse.graphql.resolver.nodeimpl

import graphql.language.Field
import graphql.language.StringValue
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLFieldDefinition
import kvasir.definitions.kg.graphql.*
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.plugins.kg.clickhouse.graphql.resolver.JoinableNode
import kvasir.utils.graphql.getStringArgument

/**
 * A special node representing a field of type RDFNode, which can be either a Resource or a Literal.
 * This requires some special handling as we potentially need to join both a collection of scalar values and a
 * collection of nested objects, and combine these into a single field in the parent node.
 */
class RDFNode(
    val field: Field,
    val fieldDefinition: GraphQLFieldDefinition,
    val context: JSONObject,
    val env: DataFetchingEnvironment,
    val parent: CompositeNode
) : JoinableNode {

    override val name: String = fieldDefinition.name
    override val nameInResult: String = field.alias ?: field.name
    val rawRDFFieldName =
        field.selectionSet?.selections?.filterIsInstance<Field>()?.find { it.name == FIELD_RAW_RDF_NAME }
            ?.let { it.alias ?: it.name } ?: FIELD_RAW_RDF_NAME
    val effectiveFieldDefinition = field.takeIf { it.name == FIELD_OBJECT_NAME }?.let {
        val selectedPredicate = it.getStringArgument(ARG_PREDICATE_NAME, env.variables)
            ?: throw IllegalArgumentException("The '_object' field requires a 'predicate' argument")
        val fqSelectedPredicate = JsonLdHelper.getFQName(selectedPredicate, context) ?: selectedPredicate
        val objectFieldId = JsonLdHelper.getUniqueVariableNameInContext(fqSelectedPredicate, context)
        // Generate a synthetic field definition for the _object field, which includes the selected predicate as a directive argument, so that it can be properly processed in the resolver and included in the Type CTE.
        GraphQLFieldDefinition.newFieldDefinition(fieldDefinition).name(objectFieldId)
            .withAppliedDirective(
                KvasirDirectives.predicateDirective.toAppliedDirective().let { predicateDirective ->
                    predicateDirective.transform { directiveBuilder ->
                        directiveBuilder.argument(
                            predicateDirective.getArgument(ARG_IRI_NAME).transform { argBuilder ->
                                argBuilder.valueLiteral(StringValue.of(selectedPredicate))
                            })
                    }
                })
            .build()
    } ?: fieldDefinition
    val rawRDFDelegate = ScalarCollectionNode(field, effectiveFieldDefinition, parent)
    val optionalResourceDelegate = run {
        if (field.selectionSet?.selections?.filterNot { it is Field && it.name == FIELD_RAW_RDF_NAME }
                ?.isNotEmpty() == true) {
            // If there are selections on the _object field other than the rawRDF field, we construct a CompositeNode as a delegate to resolve these selections.
            CompositeNode(
                context,
                parent.atChangeId,
                field,
                effectiveFieldDefinition,
                "${nameInResult}_scope",
                env,
                parent,
                env.graphQLSchema.getType(TYPE_RESOURCE) as GraphQLCompositeType?
            )
        } else null
    }
    override val joinIdentifier = optionalResourceDelegate?.joinIdentifier ?: rawRDFDelegate.joinIdentifier

    override fun getJoinStatements(): List<String> {
        // Combine joins of both delegates (if the optionalResourceDelegate is present)
        return listOf(
            rawRDFDelegate.getJoinStatements(),
            optionalResourceDelegate?.getJoinStatements() ?: emptyList()
        ).flatten().distinct()
    }

    override fun isPaginated(): Boolean {
        // The RDFNode as a whole is considered paginated if either of the delegates is paginated
        return rawRDFDelegate.isPaginated() || (optionalResourceDelegate?.isPaginated() ?: false)
    }

    override fun buildProjection(): String {
        val joinId = rawRDFDelegate.joinIdentifier
        return if (optionalResourceDelegate == null) {
            "groupUniqArray(map('$rawRDFFieldName',if($joinId.datatype='',map('@id',$joinId.value),map('@value',$joinId.value,'@type',$joinId.datatype)))) AS $nameInResult"
        } else {
            val entries =
                optionalResourceDelegate.children.joinToString { child -> "'${child.nameInResult}', ${optionalResourceDelegate.joinIdentifier}.${child.nameInResult}::Dynamic" }
            val literalExpr = "map('$rawRDFFieldName',map('@value',$joinId.value,'@type',$joinId.datatype))"
            "groupUniqArrayIf(if($joinId.datatype!='',$literalExpr,map($entries)), ${optionalResourceDelegate.joinIdentifier}.id != '' OR $joinId.datatype = '') AS $nameInResult"
        }
    }

    override fun isGroupingKey(): Boolean {
        // Composite nodes should not be grouping keys, as they are aggregated with groupUniqArray in the parent node
        return false
    }

}