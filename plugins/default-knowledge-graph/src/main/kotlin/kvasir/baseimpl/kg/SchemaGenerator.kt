package kvasir.baseimpl.kg

import com.google.common.hash.Hashing
import graphql.Scalars.*
import graphql.language.*
import graphql.scalars.ExtendedScalars
import graphql.schema.*
import kvasir.definitions.kg.KGProperty
import kvasir.definitions.kg.KGPropertyKind
import kvasir.definitions.kg.KGType
import kvasir.definitions.rdf.*
import kvasir.utils.graphql.innerType
import kvasir.utils.graphql.isScalar

class SchemaGenerator(private val types: List<KGType>, private val context: Map<String, Any>) {

    val reversedRelations = context.filterValues { it is Map<*, *> && it.keys.contains(JsonLdKeywords.reverse) }
        .map { (it.value as Map<*, *>)[JsonLdKeywords.reverse] as String to it.key }.groupBy { it.first }
        .mapValues { targetNames -> targetNames.value.map { it.second } }
    val unionTypes = mutableSetOf<GraphQLUnionType>()

    fun process(): SchemaGeneratorResult {
        val graphQLObjects = types.map { type ->
            generateGraphQLType(type)
        }
        val rdfsResourceEntryPoint = GraphQLObjectType.newObject().name("rdfs_Resource")
            .fields(
                (listOf(GraphQLFieldDefinition.newFieldDefinition().name("id").type(GraphQLID).build())
                        + graphQLObjects.flatMap { it.fields })
                    .distinctBy { it.name }).build()
        val schema = GraphQLSchema.newSchema()
            .query(
                GraphQLObjectType.newObject().name("Query")
                    .fields((listOf(rdfsResourceEntryPoint) + graphQLObjects).map { type ->
                        GraphQLFieldDefinition.newFieldDefinition().name(type.name).type(GraphQLList.list(type))
                            .arguments(DefaultKnowledgeGraph.defaultRelationArguments.plus(argumentsForType(type)))
                            .build()
                    }).build()
            )
            .additionalDirective(DefaultKnowledgeGraph.optionalDirective)
            .additionalDirective(DefaultKnowledgeGraph.filterDirective)
            .additionalDirective(DefaultKnowledgeGraph.typeDirective)
            .additionalDirective(DefaultKnowledgeGraph.predicateDirective)
            .additionalDirective(DefaultKnowledgeGraph.graphDirective)
            .additionalDirective(DefaultKnowledgeGraph.storageDirective)
        return SchemaGeneratorResult(schema, unionTypes)
    }

    private fun generateGraphQLType(type: KGType): GraphQLObjectType {
        val prefixedTypeName = graphqLCompatibleName(type.uri, context)
        val idField = GraphQLFieldDefinition.newFieldDefinition().name("id").type(GraphQLID).build()
        val typeDirective = DefaultKnowledgeGraph.typeDirective.toAppliedDirective()
        return GraphQLObjectType.newObject().name(prefixedTypeName).description(type.uri)
            .withAppliedDirective(typeDirective.transform { directiveBuilder ->
                directiveBuilder.argument(typeDirective.getArgument("iri").transform { argBuilder ->
                    argBuilder.valueLiteral(
                        StringValue.of(type.uri)
                    )
                })
            }
            )
            .fields(
                listOf(idField) + type.properties.map { property ->
                    generateGraphQLProperty(property)
                } + generateReverseProperties(type)
            ).build()
    }

    private fun generateGraphQLProperty(
        property: KGProperty,
        reverse: Boolean = false,
        overrideName: String? = null
    ): GraphQLFieldDefinition {
        val prefixedProperty = overrideName ?: graphqLCompatibleName(property.uri, context)
        val propertyType = getGraphQLPropertyType(property, unionTypes, context)
        val predicateDirective = DefaultKnowledgeGraph.predicateDirective.toAppliedDirective()
        val propertyBuilder = GraphQLFieldDefinition.newFieldDefinition()
            .withAppliedDirective(
                predicateDirective.transform { directiveBuilder ->
                    directiveBuilder.argument(
                        predicateDirective.getArgument("iri").transform { argBuilder ->
                            argBuilder.valueLiteral(
                                StringValue.of(property.uri)
                            )
                        })
                        .argument(
                            predicateDirective.getArgument("reverse").transform { argBuilder ->
                                argBuilder.valueLiteral(BooleanValue.of(reverse))
                            }
                        )
                }
            )
            .arguments(
                if (KGPropertyKind.IRI == property.kind) DefaultKnowledgeGraph.defaultRelationArguments.plus(
                    argumentsForType(propertyType)
                ) else DefaultKnowledgeGraph.defaultRelationArguments
            )
            .name(prefixedProperty)
            .description(property.uri)
            .type(GraphQLList.list(propertyType))
        return propertyBuilder.build()
    }

    private fun generateReverseProperties(type: KGType): List<GraphQLFieldDefinition> {
        // Find and generate reverse properties
        return types.flatMap { t -> t.properties.map { t to it } }
            .filter { it.second.typeRefs.contains(type.uri) && reversedRelations.containsKey(it.second.uri) }
            .flatMap { (t, p) ->
                val reverseProperty = KGProperty(p.uri, p.kind, setOf(t.uri))
                reversedRelations[p.uri]!!.map { reverseName ->
                    generateGraphQLProperty(
                        reverseProperty,
                        true,
                        reverseName
                    )
                }
            }
    }

    private fun argumentsForType(type: GraphQLOutputType): List<GraphQLArgument> {
        if (type !is GraphQLObjectType) {
            return emptyList()
        }
        return type.fieldDefinitions.map { field ->
            val argType = if (field.type.isScalar()) field.type.innerType<GraphQLScalarType>() else GraphQLID
            GraphQLArgument.newArgument().name(field.name).type(GraphQLList.list(argType)).build()
        }
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
                    XSDVocab.double, XSDVocab.decimal, XSDVocab.float -> GraphQLFloat
                    XSDVocab.string, RDFVocab.langString -> GraphQLString
                    XSDVocab.dateTime -> ExtendedScalars.DateTime
                    else -> ExtendedScalars.Json
                } as GraphQLOutputType

                KGPropertyKind.IRI -> {
                    val propertyTypeName = graphqLCompatibleName(typeRef, context)
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

    private fun graphqLCompatibleName(name: String, context: Map<String, Any>): String {
        return JsonLdHelper.compactUri(name, context, "_").takeIf { it != name } ?: run {
            val (ns, localName) = when {
                name.contains("#") -> {
                    val hashIndex = name.lastIndexOf("#")
                    name.substring(0, hashIndex) to name.substring(hashIndex + 1)
                }

                name.contains("/") -> {
                    val slashIndex = name.lastIndexOf("/")
                    name.substring(0, slashIndex) to name.substring(slashIndex + 1)
                }

                else -> throw IllegalArgumentException("Invalid URI: $name")
            }
            val encodedPrefix = Hashing.farmHashFingerprint64().hashString(ns, Charsets.UTF_8).toString()
            "ns${encodedPrefix}_$localName"
        }
    }

}

data class SchemaGeneratorResult(val schemaBuilder: GraphQLSchema.Builder, val unionTypes: Set<GraphQLUnionType>)