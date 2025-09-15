package kvasir.utils.graphql

import graphql.language.*
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import kvasir.definitions.kg.graphql.*
import kvasir.definitions.rdf.JsonLdHelper

class CheckContextVisitor(private val providedContext: Map<String, Any>) : NodeVisitorStub() {

    companion object {
        val IGNORE_TYPES = setOf(
            TYPE_QUERY,
            TYPE_MUTATION,
            TYPE_SUBSCRIPTION,
            ENUM_TRIGGER_TYPE_NAME,
            TYPE_RDF_NODE,
            TYPE_RESOURCE,
            TYPE_BOXED_LITERAL,
            TYPE_UNTYPED_RESOURCE
        )

        val IGNORE_FIELDS = setOf(
            FIELD_ID_NAME,
            FIELD_RAW_RDF_NAME,
            FIELD_OBJECT_NAME,
            FIELD_RELATIONS_NAME,
            FIELD_PREDICATES_NAME,
            FIELD_TYPES_NAME,
            FIELD_TYPENAME_NAME
        )
    }

    override fun visitTypeDefinition(
        node: TypeDefinition<*>,
        context: TraverserContext<Node<*>>
    ): TraversalControl {
        if (node is NamedNode<*> && node.name !in IGNORE_TYPES) {
            val iri = JsonLdHelper.getFQName(node.name, providedContext, "_")
            if (iri == null && !node.hasDirective("class")) {
                // Check if a type predicate is provided, otherwise throw exception
                throw MissingSemanticContextException("No semantic context found or derivable for type '${node.name}' (${context.location})")
            }
        }
        return super.visitTypeDefinition(node, context)
    }

    override fun visitFieldDefinition(node: FieldDefinition, context: TraverserContext<Node<*>>): TraversalControl? {
        val parent = context.parentNode
        // Naming of the field does not matter when at root level
        if (node.name !in IGNORE_FIELDS && parent is NamedNode && parent.name !in IGNORE_TYPES) {
            val iri = JsonLdHelper.getFQName(node.name, providedContext, "_")
            if (iri == null && !node.hasDirective("predicate")) {
                // Check if a predicate is provided, otherwise throw exception
                throw MissingSemanticContextException("No semantic context found or derivable for field '${node.name}' in type '${parent.name}' (${node.sourceLocation})")
            }
        }
        return super.visitFieldDefinition(node, context)
    }

    // TODO: check if iri argument values are valid
//    override fun visitArgument(node: Argument, context: TraverserContext<Node<*>>): TraversalControl {
//        val parent = context.parentNode
//        when {
//            parent is Directive && parent.name in setOf("predicate", "type") -> {
//                if (node.name == "iri") {
//                    val value = (node.value as StringValue).value
//                    val iri = resolveNameAsIri(value, RDF_PREFIX_SEPARATOR)
//                    if (iri == null) {
//                        throw MissingSemanticContextException("No semantic context found or derivable for argument '${node.name}' (value: '$value') in directive '${parent.name}' (${node.sourceLocation})")
//                    }
//                }
//            }
//        }
//        return super.visitArgument(node, context)
//    }
}

class MissingSemanticContextException(message: String) : IllegalArgumentException(message)