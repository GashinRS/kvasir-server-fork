package kvasir.utils.kg

import graphql.Scalars.GraphQLBoolean
import graphql.Scalars.GraphQLFloat
import graphql.Scalars.GraphQLID
import graphql.Scalars.GraphQLInt
import graphql.Scalars.GraphQLString
import graphql.scalars.ExtendedScalars
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLList
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLTypeReference
import graphql.schema.GraphQLUnionType
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.RDFVocab
import kvasir.definitions.rdf.XSDVocab

class SchemaGenerator(private val types: List<KGType>, private val context: Map<String, Any>) {

    fun process(): SchemaGeneratorResult {
        val unionTypes = mutableSetOf<GraphQLUnionType>()
        val graphQLObjects = types.map { type ->
            val prefixedTypeName = JsonLdHelper.compactUri(type.uri, context, "_")
            val idField = GraphQLFieldDefinition.newFieldDefinition().name("id").type(GraphQLID).build()
            GraphQLObjectType.newObject().name(prefixedTypeName).description(type.uri).fields(
                listOf(idField) + type.properties.map { property ->
                    val prefixedProperty = JsonLdHelper.compactUri(property.uri, context, "_")
                    val propertyType = getGraphQLPropertyType(property, unionTypes, context)
                    val propertyBuilder = GraphQLFieldDefinition.newFieldDefinition()
                        .arguments(if (KGPropertyKind.IRI == property.kind) AbstractKnowledgeGraph.defaultRelationArguments else emptyList())
                        .name(prefixedProperty)
                        .description(property.uri)
                        .type(GraphQLList.list(propertyType))
                    propertyBuilder.build()
                }
            ).build()
        }
        val rdfsResourceEntryPoint = GraphQLObjectType.newObject().name("rdfs_Resource")
            .fields(graphQLObjects.flatMap { it.fields }.distinctBy { it.name }).build()
        val schema = GraphQLSchema.newSchema()
            .query(
                GraphQLObjectType.newObject().name("Query")
                    .fields((listOf(rdfsResourceEntryPoint) + graphQLObjects).map { type ->
                        GraphQLFieldDefinition.newFieldDefinition().name(type.name).type(GraphQLList.list(type))
                            .arguments(AbstractKnowledgeGraph.defaultRelationArguments)
                            .build()
                    }).build()
            )
            .additionalDirective(AbstractKnowledgeGraph.optionalDirective)
            .additionalDirective(AbstractKnowledgeGraph.filterDirective)
        return SchemaGeneratorResult(schema, unionTypes)
    }

    private fun getGraphQLPropertyType(
        property: KGProperty,
        unionTypes: MutableSet<GraphQLUnionType>,
        context: Map<String, Any>
    ): GraphQLOutputType {
        val outputTypes = property.typeRefs.map { typeRef ->
            when (property.kind) {
                KGPropertyKind.Literal -> when (typeRef) {
                    XSDVocab.boolean -> GraphQLBoolean
                    XSDVocab.int, XSDVocab.integer, XSDVocab.long -> GraphQLInt
                    XSDVocab.double, XSDVocab.decimal -> GraphQLFloat
                    XSDVocab.string, RDFVocab.langString -> GraphQLString
                    else -> ExtendedScalars.Json
                } as GraphQLOutputType

                KGPropertyKind.IRI -> {
                    val propertyTypeName = JsonLdHelper.compactUri(typeRef, context, "_")
                    GraphQLTypeReference.typeRef(propertyTypeName)
                }

                else -> throw IllegalArgumentException("Unsupported property kind: ${property.kind}")
            }
        }
        return if (outputTypes.size > 1) {
            if (outputTypes.any { it is GraphQLScalarType }) {
                // If there are scalar types in the union, use JSON
                ExtendedScalars.Json
            } else {
                val graphQLOutputTypeReferences = outputTypes.filterIsInstance<GraphQLTypeReference>()
                val unionName = graphQLOutputTypeReferences.joinToString("Or") { it.name }
                if (unionTypes.none { it.name == unionName }) {
                    val unionType = GraphQLUnionType.newUnionType()
                        .name(unionName)
                        .possibleTypes(*graphQLOutputTypeReferences.toTypedArray())
                        .build()
                    unionTypes.add(unionType)
                    unionType
                } else {
                    GraphQLTypeReference.typeRef(unionName)
                }
            }
        } else {
            outputTypes.first()
        }
    }

}

data class SchemaGeneratorResult(val schemaBuilder: GraphQLSchema.Builder, val unionTypes: Set<GraphQLUnionType>)