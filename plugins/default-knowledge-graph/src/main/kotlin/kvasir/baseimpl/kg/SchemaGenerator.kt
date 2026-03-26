package kvasir.baseimpl.kg

import graphql.Scalars.*
import graphql.language.BooleanValue
import graphql.language.StringValue
import graphql.scalars.ExtendedScalars
import graphql.schema.*
import kvasir.definitions.kg.KGProperty
import kvasir.definitions.kg.KGPropertyKind
import kvasir.definitions.kg.KGType
import kvasir.definitions.kg.KGTypeReference
import kvasir.definitions.kg.graphql.*
import kvasir.definitions.rdf.*
import kvasir.utils.graphql.innerType
import kvasir.utils.graphql.isScalar

class SchemaGenerator(private val types: List<KGType>, private val context: Map<String, Any>) {

    val reversedRelations = context.filterValues { it is Map<*, *> && it.keys.contains(JsonLdKeywords.reverse) }
        .map {
            val reversePredicate = (it.value as Map<*, *>)[JsonLdKeywords.reverse] as String
            (JsonLdHelper.getFQName(reversePredicate, context) ?: reversePredicate) to it.key
        }
        .groupBy { it.first }
        .mapValues { targetNames -> targetNames.value.map { it.second } }

    fun process(): SchemaGeneratorResult {
        val graphQLObjects = types.map { type ->
            generateGraphQLType(type)
        }
        val schema = GraphQLSchema.newSchema()
            .query(
                GraphQLObjectType.newObject().name("Query")
                    .fields(
                        // Generate entry-points
                        (listOf(KvasirTypes.Resource) + graphQLObjects).filterIsInstance<GraphQLNamedOutputType>()
                            .map { type ->
                                GraphQLFieldDefinition.newFieldDefinition().name(type.name).type(GraphQLList.list(type))
                                    .arguments(
                                        if (type.name == TYPE_RESOURCE) KvasirTypes.defaultRelationArguments else KvasirTypes.defaultRelationArguments.plus(
                                            argumentsForType(type)
                                        )
                                    )
                                    .build()
                            }).build()
            )
            .additionalType(KvasirTypes.BoxedLiteral)
            .additionalType(KvasirTypes.UntypedResource)
            .additionalDirectives(KvasirDirectives.all)
        return SchemaGeneratorResult(schema)
    }

    private fun generateGraphQLType(type: KGType): GraphQLObjectType {
        val prefixedTypeName = JsonLdHelper.getUniqueVariableNameInContext(type.uri, context)
        val typeDirective = KvasirDirectives.classDirective.toAppliedDirective()
        return GraphQLObjectType.newObject().name(prefixedTypeName).description(type.uri)
            .withInterfaces(KvasirTypes.Resource, KvasirTypes.RDFNode)
            .withAppliedDirective(typeDirective.transform { directiveBuilder ->
                directiveBuilder.argument(typeDirective.getArgument(ARG_IRI_NAME).transform { argBuilder ->
                    argBuilder.valueLiteral(
                        StringValue.of(type.uri)
                    )
                })
            }
            )
            .fields(
                KvasirTypes.commonResourceFields + type.properties.filterNot { it.uri == RDFVocab.type }
                    .map { property ->
                        generateGraphQLProperty(property)
                    } + generateReverseProperties(type)
            ).build()
    }

    private fun generateGraphQLProperty(
        property: KGProperty,
        reverse: Boolean = false,
        overrideName: String? = null
    ): GraphQLFieldDefinition {
        val prefixedProperty = overrideName ?: JsonLdHelper.getUniqueVariableNameInContext(property.uri, context)
        val propertyType = getGraphQLPropertyType(property, context)
        val predicateDirective = KvasirDirectives.predicateDirective.toAppliedDirective()
        val propertyBuilder = GraphQLFieldDefinition.newFieldDefinition()
            .withAppliedDirective(
                predicateDirective.transform { directiveBuilder ->
                    directiveBuilder.argument(
                        predicateDirective.getArgument(ARG_IRI_NAME).transform { argBuilder ->
                            argBuilder.valueLiteral(
                                StringValue.of(property.uri)
                            )
                        })
                        .argument(
                            predicateDirective.getArgument(ARG_REVERSE_NAME).transform { argBuilder ->
                                argBuilder.valueLiteral(BooleanValue.of(reverse))
                            }
                        )
                }
            )
            .arguments(
                if (property.typeRefs.any { it.kind == KGPropertyKind.IRI }) KvasirTypes.defaultRelationArguments.plus(
                    argumentsForType(propertyType)
                ) else KvasirTypes.defaultRelationArguments
            )
            .name(prefixedProperty)
            .description(property.uri)
            .type(GraphQLList.list(propertyType))
        return propertyBuilder.build()
    }

    private fun generateReverseProperties(type: KGType): List<GraphQLFieldDefinition> {
        // Find and generate reverse properties
        return types.flatMap { t -> t.properties.map { t to it } }
            .filter { it.second.typeRefs.any { it.name == type.uri } && reversedRelations.containsKey(it.second.uri) }
            .flatMap { (t, p) ->
                val reverseProperty = KGProperty(p.uri, setOf(KGTypeReference(KGPropertyKind.IRI, t.uri)))
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
        // TODO: filter on limited list of Kvasir built-ins instead of ignoring all fields starting with "_"?
        return type.fieldDefinitions.filterNot { it.name.startsWith("_") }.map { field ->
            val argType = if (field.type.isScalar()) field.type.innerType() else GraphQLID
            GraphQLArgument.newArgument().name(field.name).type(GraphQLList.list(argType)).build()
        }
    }

    private fun getGraphQLPropertyType(
        property: KGProperty,
        context: Map<String, Any>
    ): GraphQLOutputType {
        val outputTypes = property.typeRefs.map { typeRef ->
            when (typeRef.kind) {
                KGPropertyKind.Literal -> when (typeRef.name) {
                    XSDVocab.boolean -> GraphQLBoolean
                    XSDVocab.int, XSDVocab.integer, XSDVocab.long -> GraphQLInt
                    XSDVocab.double, XSDVocab.decimal, XSDVocab.float -> GraphQLFloat
                    XSDVocab.string, RDFVocab.langString -> GraphQLString
                    XSDVocab.dateTime -> ExtendedScalars.DateTime
                    XSDVocab.date -> ExtendedScalars.Date
                    XSDVocab.time -> ExtendedScalars.Time
                    else -> ExtendedScalars.Json
                } as GraphQLOutputType

                KGPropertyKind.IRI -> {
                    GraphQLTypeReference.typeRef(
                        if (typeRef.name == RDFSVocab.Resource) {
                            TYPE_RESOURCE
                        } else {
                            JsonLdHelper.getUniqueVariableNameInContext(typeRef.name, context)
                        }
                    )
                }

                else -> throw IllegalArgumentException("Unsupported property kind: ${typeRef.kind}")
            }
        }
        return if (outputTypes.size > 1) {
            if (outputTypes.any { it is GraphQLScalarType }) {
                // If there are scalar types in the union, use the generic RDFNode type (which can be a boxed literal or a Resource).
                GraphQLTypeReference.typeRef(TYPE_RDF_NODE)
            } else {
                // Else use the generic Resource type. If more specific typing is required, clients should use the Slices feature!
                GraphQLTypeReference.typeRef(TYPE_RESOURCE)
            }
        } else {
            outputTypes.first()
        }
    }

}

data class SchemaGeneratorResult(val schemaBuilder: GraphQLSchema.Builder)