package kvasir.definitions.graphql

import graphql.language.*
import graphql.parser.Parser
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import graphql.util.TreeTransformerUtil
import kvasir.definitions.rdf.RDFVocab

object QueryUtils {

    fun parseQueryWithContext(queryStr: String, context: Map<String, Any>): Document {
        val queryDoc = Parser.parse(queryStr)
        val contextualizedDoc =
            AstTransformer().transform(queryDoc, ContextualizingQueryVisitor(context))
        return contextualizedDoc as Document
    }

}

class ContextualizingQueryVisitor(providedContext: Map<String, Any>) : NodeVisitorStub() {

    companion object {
        private const val PREFIX_SEPARATOR = "_"
    }

    private val fullContext = providedContext.plus("__typename" to RDFVocab.type)

    override fun visitField(node: Field, traverserContext: TraverserContext<Node<*>>): TraversalControl {
        val changedField = node.transform {
            resolveIri(node.name)?.let { iri ->
                it.directive(buildContextDirective(iri))
            }
        }
        return TreeTransformerUtil.changeNode(traverserContext, changedField)
    }

    override fun visitInlineFragment(
        node: InlineFragment,
        traverserContext: TraverserContext<Node<*>>
    ): TraversalControl {
        val changedFragment = node.transform {
            resolveIri(node.typeCondition.name)?.let { iri ->
                it.directive(buildContextDirective(iri))
            }
        }
        return TreeTransformerUtil.changeNode(traverserContext, changedFragment)
    }

    override fun visitFragmentDefinition(
        node: FragmentDefinition,
        traverserContext: TraverserContext<Node<*>>
    ): TraversalControl {
        val changedFragmentDefinition = node.transform {
            resolveIri(node.typeCondition.name)?.let { iri ->
                it.directive(buildContextDirective(iri))
            }
        }
        return TreeTransformerUtil.changeNode(traverserContext, changedFragmentDefinition)
    }

    override fun visitArgument(node: Argument, traverserContext: TraverserContext<Node<*>>): TraversalControl {
        val changedFragmentDefinition = node.transform {
            resolveIri(node.name)?.let { iri ->
                it.additionalData("iri", iri)
            }
            val argVal = node.value
            if (argVal is StringValue) {
                resolveIri(argVal.value)?.let { iri ->
                    it.value(StringValue.of(iri))
                }
            }
        }
        return TreeTransformerUtil.changeNode(traverserContext, changedFragmentDefinition)
    }

    private fun buildContextDirective(iri: String): Directive {
        return Directive.newDirective().name("context")
            .argument(
                Argument.newArgument(
                    "iri",
                    StringValue.of(iri)
                ).build()
            )
            .build()
    }

    private fun resolveIri(name: String): String? {
        return fullContext[name]?.toString() ?: name.takeIf { it.contains(PREFIX_SEPARATOR) }?.let { prefixedName ->
            val (prefix, localName) = prefixedName.split(PREFIX_SEPARATOR)
            fullContext[prefix]?.let { prefixIri ->
                "$prefixIri$localName"
            }
        }
    }
}