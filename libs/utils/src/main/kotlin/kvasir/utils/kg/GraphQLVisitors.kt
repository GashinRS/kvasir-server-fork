package kvasir.utils.kg

import graphql.language.Argument
import graphql.language.Directive
import graphql.language.FieldDefinition
import graphql.language.NamedNode
import graphql.language.Node
import graphql.language.NodeVisitorStub
import graphql.language.StringValue
import graphql.language.TypeDefinition
import graphql.util.TraversalControl
import graphql.util.TraverserContext

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


    override fun visitTypeDefinition(
        node: TypeDefinition<*>,
        context: TraverserContext<Node<*>>
    ): TraversalControl {
        if (node is NamedNode<*> && node.name !in listOf("Query", "Mutation", "Subscription")) {
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
        if (node.name != "id" && parent is NamedNode && parent.name !in listOf("Query", "Mutation", "Subscription")) {
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