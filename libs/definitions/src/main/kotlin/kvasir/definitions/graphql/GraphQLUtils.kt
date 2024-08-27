package kvasir.definitions.graphql

import graphql.language.*
import graphql.parser.Parser
import graphql.schema.idl.SchemaPrinter
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import graphql.util.TreeTransformerUtil
import kvasir.definitions.rdf.RDFVocab

object GraphQLUtils {

    fun parseDocumentWithContext(queryStr: String, context: Map<String, Any>): Document {
        val queryDoc = Parser.parse(queryStr)
        val contextualizedDoc =
            AstTransformer().transform(queryDoc, ContextualizingQueryVisitor(context))
        return contextualizedDoc as Document
    }

    fun parseDocument(queryStr: String): Document {
        return Parser.parse(queryStr)
    }

    fun schemaToSDL(schema: Document): String {
        return SchemaPrinter().print(schema)
    }

}

class ContextualizingQueryVisitor(providedContext: Map<String, Any>) : NodeVisitorStub() {

    companion object {
        private const val GRAPHQL_NAME_PREFIX_SEPARATOR = "_"
        private const val RDF_PREFIX_SEPARATOR = ":"
    }

    private val fullContext = providedContext.plus("__typename" to RDFVocab.type)

    override fun visitField(node: Field, traverserContext: TraverserContext<Node<*>>): TraversalControl {
        val changedField = node.transform {
            resolveNameAsIri(node.name)?.let { iri ->
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
            resolveNameAsIri(node.typeCondition.name)?.let { iri ->
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
            resolveNameAsIri(node.typeCondition.name)?.let { iri ->
                it.directive(buildContextDirective(iri))
            }
        }
        return TreeTransformerUtil.changeNode(traverserContext, changedFragmentDefinition)
    }

    override fun visitArgument(node: Argument, traverserContext: TraverserContext<Node<*>>): TraversalControl {
        val changedFragmentDefinition = node.transform {
            resolveNameAsIri(node.name)?.let { iri ->
                it.additionalData("iri", iri)
            }
            val argVal = node.value
            if (argVal is StringValue) {
                resolveNameAsIri(argVal.value, RDF_PREFIX_SEPARATOR)?.let { iri ->
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

    private fun resolveNameAsIri(name: String, separator: String = GRAPHQL_NAME_PREFIX_SEPARATOR): String? {
        return fullContext[name]?.toString() ?: name.takeIf { it.contains(separator) }?.let { prefixedName ->
            val (prefix, localName) = prefixedName.split(separator)
            fullContext[prefix]?.let { prefixIri ->
                "$prefixIri$localName"
            }
        }
    }
}