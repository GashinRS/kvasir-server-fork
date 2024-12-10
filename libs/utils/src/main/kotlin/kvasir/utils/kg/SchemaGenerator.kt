package kvasir.utils.kg

import com.google.common.hash.Hashing
import graphql.Scalars.*
import graphql.language.*
import graphql.scalars.ExtendedScalars
import graphql.schema.*
import graphql.schema.idl.TypeDefinitionRegistry
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.rdf.RDFVocab
import kvasir.definitions.rdf.XSDVocab
import kvasir.utils.graphql.innerType
import kvasir.utils.graphql.isScalar

class SchemaGenerator(private val types: List<KGType>, private val context: Map<String, Any>) {

    fun process(): SchemaGeneratorResult {
        val unionTypes = mutableSetOf<GraphQLUnionType>()
        val graphQLObjects = types.map { type ->
            val prefixedTypeName = graphqLCompatibleName(type.uri, context)
            val idField = GraphQLFieldDefinition.newFieldDefinition().name("id").type(GraphQLID).build()
            val totalCount =
                GraphQLFieldDefinition.newFieldDefinition().name(graphqLCompatibleName(KvasirVocab.totalCount, context))
                    .description("Total number of selected '${type.uri}' instances")
                    .type(GraphQLInt).build()
            val typeDirective = AbstractKnowledgeGraph.typeDirective.toAppliedDirective()
            GraphQLObjectType.newObject().name(prefixedTypeName).description(type.uri)
                .withAppliedDirective(typeDirective.transform { directiveBuilder ->
                    directiveBuilder.argument(typeDirective.getArgument("iri").transform { argBuilder ->
                        argBuilder.valueLiteral(
                            StringValue.of(type.uri)
                        )
                    })
                }
                )
                .fields(
                    listOf(idField, totalCount) + type.properties.map { property ->
                        val prefixedProperty = graphqLCompatibleName(property.uri, context)
                        val propertyType = getGraphQLPropertyType(property, unionTypes, context)
                        val predicateDirective = AbstractKnowledgeGraph.predicateDirective.toAppliedDirective()
                        val propertyBuilder = GraphQLFieldDefinition.newFieldDefinition()
                            .withAppliedDirective(
                                predicateDirective.transform { directiveBuilder ->
                                    directiveBuilder.argument(
                                        predicateDirective.getArgument("iri").transform { argBuilder ->
                                            argBuilder.valueLiteral(
                                                StringValue.of(property.uri)
                                            )
                                        })
                                }
                            )
                            .arguments(
                                if (KGPropertyKind.IRI == property.kind) AbstractKnowledgeGraph.defaultRelationArguments.plus(
                                    argumentsForType(propertyType)
                                ) else AbstractKnowledgeGraph.defaultRelationArguments
                            )
                            .name(prefixedProperty)
                            .description(property.uri)
                            .type(GraphQLList.list(propertyType))
                        propertyBuilder.build()
                    }
                ).build()
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
                            .arguments(AbstractKnowledgeGraph.defaultRelationArguments.plus(argumentsForType(type)))
                            .build()
                    }).build()
            )
            .additionalDirective(AbstractKnowledgeGraph.optionalDirective)
            .additionalDirective(AbstractKnowledgeGraph.filterDirective)
            .additionalDirective(AbstractKnowledgeGraph.typeDirective)
            .additionalDirective(AbstractKnowledgeGraph.predicateDirective)
            .additionalDirective(AbstractKnowledgeGraph.graphDirective)
        return SchemaGeneratorResult(schema, unionTypes)
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
                    XSDVocab.double, XSDVocab.decimal -> GraphQLFloat
                    XSDVocab.string, RDFVocab.langString -> GraphQLString
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

internal fun TypeDefinitionRegistry.addKvasirDirectives() {
    // TODO: avoid duplication with directives added to the GraphQLSchema
    this
        .addAll(
            listOf(
                DirectiveDefinition.newDirectiveDefinition().name("predicate").inputValueDefinitions(
                    listOf(
                        InputValueDefinition.newInputValueDefinition().name("iri").type(
                            TypeName.newTypeName("String").build()
                        ).build(),
                        InputValueDefinition.newInputValueDefinition().name("reverse").type(
                            TypeName.newTypeName("Boolean").build()
                        ).build()
                    )
                )
                    .directiveLocation(DirectiveLocation.newDirectiveLocation().name("FIELD_DEFINITION").build())
                    .build(),
                DirectiveDefinition.newDirectiveDefinition().name("class").inputValueDefinitions(
                    listOf(
                        InputValueDefinition.newInputValueDefinition().name("iri").type(
                            TypeName.newTypeName("String").build()
                        ).build()
                    )
                )
                    .directiveLocations(
                        listOf(
                            DirectiveLocation.newDirectiveLocation().name("OBJECT").build(),
                            DirectiveLocation.newDirectiveLocation().name("INTERFACE").build()
                        )
                    )
                    .build(),
                DirectiveDefinition.newDirectiveDefinition().name("shape").inputValueDefinitions(
                    listOf(
                        InputValueDefinition.newInputValueDefinition().name("minCount")
                            .type(TypeName.newTypeName("Int").build()).build(),
                        InputValueDefinition.newInputValueDefinition().name("maxCount")
                            .type(TypeName.newTypeName("Int").build()).build(),
                        InputValueDefinition.newInputValueDefinition().name("minExclusive")
                            .type(TypeName.newTypeName("String").build()).build(),
                        InputValueDefinition.newInputValueDefinition().name("maxExclusive")
                            .type(TypeName.newTypeName("String").build()).build(),
                        InputValueDefinition.newInputValueDefinition().name("minInclusive")
                            .type(TypeName.newTypeName("String").build()).build(),
                        InputValueDefinition.newInputValueDefinition().name("maxInclusive")
                            .type(TypeName.newTypeName("String").build()).build(),
                        InputValueDefinition.newInputValueDefinition().name("minLength")
                            .type(TypeName.newTypeName("Int").build()).build(),
                        InputValueDefinition.newInputValueDefinition().name("maxLength")
                            .type(TypeName.newTypeName("Int").build()).build(),
                        InputValueDefinition.newInputValueDefinition().name("pattern")
                            .type(TypeName.newTypeName("String").build()).build(),
                        InputValueDefinition.newInputValueDefinition().name("flags")
                            .type(TypeName.newTypeName("String").build()).build(),
                        InputValueDefinition.newInputValueDefinition().name("hasValue")
                            .type(TypeName.newTypeName("String").build()).build(),
                        InputValueDefinition.newInputValueDefinition().name("in")
                            .type(ListType(TypeName.newTypeName("String").build())).build(),
                    )
                )
                    .directiveLocation(DirectiveLocation.newDirectiveLocation().name("FIELD_DEFINITION").build())
                    .build(),
            )
        )
}