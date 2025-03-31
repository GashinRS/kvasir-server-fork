package kvasir.utils.graphql

import graphql.language.*
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import kvasir.definitions.kg.graphql.*

abstract class KvasirNodeVisitor(protected val providedContext: Map<String, Any>) : NodeVisitorStub() {

    companion object {
        const val GRAPHQL_NAME_PREFIX_SEPARATOR = "_"
        const val RDF_PREFIX_SEPARATOR = ":"
    }

    protected fun resolveNameAsIri(name: String, separator: String = GRAPHQL_NAME_PREFIX_SEPARATOR): String? {
        return providedContext[name]?.toString() ?: name.takeIf { it.contains(separator) }?.let { prefixedName ->
            val (prefix, localName) = prefixedName.split(separator)
            providedContext[prefix]?.let { prefixIri ->
                "$prefixIri$localName"
            }
        }
    }
}

class CheckContextVisitor(providedContext: Map<String, Any>) : KvasirNodeVisitor(providedContext) {

    companion object {
        val IGNORE_TYPES = setOf(TYPE_QUERY, TYPE_MUTATION, TYPE_SUBSCRIPTION, ENUM_TRIGGER_TYPE_NAME, TYPE_RDF_NODE, TYPE_RESOURCE, TYPE_BOXED_LITERAL)
    }

    override fun visitTypeDefinition(
        node: TypeDefinition<*>,
        context: TraverserContext<Node<*>>
    ): TraversalControl {
        if (node is NamedNode<*> && node.name !in IGNORE_TYPES) {
            val iri = resolveNameAsIri(node.name)
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
        if (node.name != "id" && parent is NamedNode && parent.name !in IGNORE_TYPES) {
            val iri = resolveNameAsIri(node.name)
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