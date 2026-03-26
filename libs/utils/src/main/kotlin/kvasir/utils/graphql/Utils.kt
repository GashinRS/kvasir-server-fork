package kvasir.utils.graphql

import graphql.TypeResolutionEnvironment
import graphql.language.*
import graphql.schema.*
import graphql.schema.idl.TypeUtil
import kvasir.definitions.kg.graphql.*
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.getJsonArray
import kvasir.utils.json.convertToJsonMap

fun getFQName(nodeName: String, node: DirectivesContainer<*>, context: JSONObject): String {
    // predicate directive takes precedence over prefix before underscore in graphql schema
    return run {
        (node.getDirectiveArg<StringValue>(DIRECTIVE_PREDICATE_NAME, ARG_IRI_NAME)?.value
            ?: node.getDirectiveArg<StringValue>(DIRECTIVE_CLASS_NAME, ARG_IRI_NAME)?.value)
            ?.let { JsonLdHelper.getFQName(it, context) ?: it }
    } ?: JsonLdHelper.getFQName(nodeName, context, "_")?.takeIf { it != nodeName }
    ?: throw IllegalArgumentException("No semantic context found for $nodeName")
}

fun getFQName(node: GraphQLDirectiveContainer, context: Map<String, Any>): String {
    // predicate directive takes precedence over prefix before underscore in graphql schema
    return run {
        (node.getAppliedDirective(DIRECTIVE_PREDICATE_NAME)?.getArgument(ARG_IRI_NAME)?.getValue<String>()
            ?: node.getAppliedDirective(DIRECTIVE_CLASS_NAME)?.getArgument(ARG_IRI_NAME)?.getValue<String>())
            ?.let { JsonLdHelper.getFQName(it, context) ?: it }
    } ?: JsonLdHelper.getFQName(node.name, context, "_")?.takeIf { it != node.name }
    ?: throw IllegalArgumentException("No semantic context found for ${node.name}")
}

fun getFQName(field: Field, context: Map<String, Any>): String {
    // predicate directive takes precedence over prefix before underscore in graphql schema
    return run {
        (field.getDirectiveArg<StringValue>(DIRECTIVE_PREDICATE_NAME, ARG_IRI_NAME)?.value
            ?: field.getDirectiveArg<StringValue>(DIRECTIVE_CLASS_NAME, ARG_IRI_NAME)?.value)
            ?.let { JsonLdHelper.getFQName(it, context) ?: it }
    } ?: JsonLdHelper.getFQName(field.name, context, "_")?.takeIf { it != field.name }
    ?: throw IllegalArgumentException("No semantic context found for ${field.name}")
}

class RDFClassTypeResolver(private val context: JSONObject) : TypeResolver {
    override fun getType(env: TypeResolutionEnvironment): GraphQLObjectType {
        val target = convertToJsonMap(env.getObject())
        val fieldType = env.fieldType.innerType<GraphQLNamedType>()
        val defaultResolvedType =
            if (fieldType.name == TYPE_RESOURCE) KvasirTypes.UntypedResource else KvasirTypes.BoxedLiteral
        return (target[FIELD_TYPENAME_NAME] as String?)?.let {
            env.schema.getObjectType(it)
        } ?: run {
            // When no explicit __typename was set, use the _types field to access the RDF classes for this instance and select the first entry
            val fqClassNames = target.getJsonArray<String?>(FIELD_TYPES_NAME)?.filterNotNull() ?: emptyList()
            if (fqClassNames.isNotEmpty()) {
                val fqClassName = fqClassNames.min()
                env.schema.allTypesAsList.filterIsInstance<GraphQLObjectType>().find { objectType ->
                    (objectType.getDirectiveArg<StringValue>(
                        DIRECTIVE_CLASS_NAME,
                        ARG_IRI_NAME
                    )?.value ?: JsonLdHelper.getFQName(objectType.name, context, "_")) == fqClassName
                } ?: defaultResolvedType
            } else {
                // Default to BoxedLiteral
                defaultResolvedType
            }
        }
    }
}

/**
 * Replaces the inner type of a (potentially wrapped) GraphQL type with a new type.
 */
fun replaceInnerType(type: Type<*>, newType: Type<*>): Type<*> {
    return when {
        TypeUtil.isNonNull(type) -> {
            val result = replaceInnerType(TypeUtil.unwrapOne(type), newType)
            NonNullType.newNonNullType().type(result).build()
        }

        TypeUtil.isList(type) -> {
            val result = replaceInnerType(TypeUtil.unwrapOne(type), newType)
            ListType.newListType().type(result).build()
        }

        else -> newType
    }
}

object SchemaConversions {
    fun convertInterface(type: GraphQLInterfaceType, ignoreFields: Set<String>? = null): InterfaceTypeDefinition {
        return InterfaceTypeDefinition.newInterfaceTypeDefinition()
            .name(type.name)
            .implementz(type.interfaces.map { TypeName.newTypeName(it.name).build() })
            .definitions(type.fieldDefinitions.filterNot { ignoreFields?.contains(it.name) ?: false }
                .map(::convertField))
            .build()
    }

    fun convertObject(type: GraphQLObjectType, ignoreFields: Set<String>? = null): ObjectTypeDefinition {
        return ObjectTypeDefinition.newObjectTypeDefinition()
            .name(type.name)
            .implementz(type.interfaces.map { TypeName.newTypeName(it.name).build() })
            .fieldDefinitions(type.fieldDefinitions.filterNot { ignoreFields?.contains(it.name) ?: false }
                .map(::convertField))
            .build()
    }

    fun convertField(field: GraphQLFieldDefinition): FieldDefinition {
        return FieldDefinition.newFieldDefinition()
            .name(field.name)
            .type(convertType(field.type))
            .inputValueDefinitions(field.arguments.map(::convertArgument))
            .build()
    }

    fun convertDirective(directive: GraphQLDirective): DirectiveDefinition {
        return DirectiveDefinition.newDirectiveDefinition()
            .name(directive.name)
            .directiveLocations(
                directive.validLocations()
                    .map { location -> DirectiveLocation.newDirectiveLocation().name(location.name).build() })
            .repeatable(directive.isRepeatable)
            .inputValueDefinitions(directive.arguments.map(::convertArgument))
            .build()
    }

    fun convertType(type: GraphQLType): Type<*> {
        return when {
            GraphQLTypeUtil.isList(type) -> ListType.newListType(convertType(GraphQLTypeUtil.unwrapOne(type))).build()
            GraphQLTypeUtil.isNonNull(type) -> NonNullType.newNonNullType()
                .type(convertType(GraphQLTypeUtil.unwrapNonNull(type))).build()

            type is GraphQLNamedType -> TypeName.newTypeName().name(type.name).build()
            else -> throw IllegalArgumentException("Unsupported GraphQL type $type")
        }
    }

    fun convertArgument(argument: GraphQLArgument): InputValueDefinition {
        return InputValueDefinition.newInputValueDefinition()
            .name(argument.name)
            .type(convertType(argument.type))
            .build()
    }
}