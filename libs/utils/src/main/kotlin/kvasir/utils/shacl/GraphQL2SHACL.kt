package kvasir.utils.shacl

import graphql.language.IntValue
import graphql.language.StringValue
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import kvasir.definitions.kg.graphql.*
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.utils.graphql.*
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.impl.DynamicModelFactory
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.model.util.RDFCollections
import org.eclipse.rdf4j.model.vocabulary.*
import java.util.*


class GraphQL2SHACL(graphql: String, private val context: Map<String, Any>) {

    private val typeRegistry = SchemaParser().parse(graphql)
    private val rdfFactory = SimpleValueFactory.getInstance()
    private val shapeDocBaseIri = "kvasir:shapes:${UUID.randomUUID()}:"

    private val graphQLSchema = run {
        typeRegistry.addKvasirDirectives()
        SchemaGenerator().makeExecutableSchema(typeRegistry, RuntimeWiring.newRuntimeWiring().build())
    }

    private val typeToShapes = graphQLSchema.allTypesAsList.filterIsInstance<GraphQLInputObjectType>()
        .map { typeToSHACL(it) }

    fun getInsertSHACL(): Model {
        return graphQLSchema.mutationType?.let { mutationType ->
            val statements = typeToShapes.flatMap { it.second }
            val allowedTypes =
                mutationType.fieldDefinitions.filter { it.name.startsWith("insert") || it.name.startsWith("add") }
                    .flatMap { fieldDefinition ->
                        fieldDefinition.arguments.map {
                            getFQName(
                                it.type.innerType<GraphQLInputObjectType>(),
                                context
                            )
                        }
                    }
                    .map { rdfFactory.createIRI(it) }
            generateSHACL(statements, allowedTypes)
        } ?: DynamicModelFactory().createEmptyModel()
    }

    fun getDeleteSHACL(): Model {
        return graphQLSchema.mutationType?.let { mutationType ->
            val statements = typeToShapes.flatMap { it.second }
            val allowedTypes =
                mutationType.fieldDefinitions.filter { it.name.startsWith("delete") || it.name.startsWith("remove") }
                    .flatMap { fieldDefinition ->
                        fieldDefinition.arguments.map {
                            getFQName(
                                it.type.innerType<GraphQLInputObjectType>(),
                                context
                            )
                        }
                    }
                    .map { rdfFactory.createIRI(it) }
            generateSHACL(statements, allowedTypes)
        } ?: DynamicModelFactory().createEmptyModel()
    }

    private fun generateSHACL(statements: List<Statement>, allowedTypes: List<IRI>): Model {
        val model = DynamicModelFactory().createEmptyModel()
        model.addAll(statements)
        model.addAll(getFilterShape(model, allowedTypes))
        return model
    }

    fun hasMutations(): Boolean {
        return graphQLSchema.mutationType != null
    }

    private fun getFilterShape(shacl: Model, availableTypes: List<IRI>): Model {
        val filterNodeShapeSubject = rdfFactory.createIRI("${shapeDocBaseIri}MustHaveTypeShape")
        val typeInSubject = rdfFactory.createBNode()
        val propertySubject = rdfFactory.createBNode()
        RDFCollections.asRDF(availableTypes, typeInSubject, shacl)
        shacl.addAll(
            listOf(
                rdfFactory.createStatement(
                    filterNodeShapeSubject,
                    RDF.TYPE,
                    SHACL.NODE_SHAPE
                ),
                rdfFactory.createStatement(
                    filterNodeShapeSubject,
                    SHACL.TARGET_PROP,
                    rdfFactory.createIRI("${DASH.NAMESPACE}AllSubjects")
                ),
                rdfFactory.createStatement(
                    filterNodeShapeSubject,
                    SHACL.PROPERTY,
                    propertySubject
                ),
                rdfFactory.createStatement(
                    propertySubject,
                    RDF.TYPE,
                    SHACL.PROPERTY_SHAPE
                ),
                rdfFactory.createStatement(
                    propertySubject,
                    SHACL.MIN_COUNT,
                    rdfFactory.createLiteral(1)
                ),
                rdfFactory.createStatement(propertySubject, SHACL.PATH, RDF.TYPE),
                rdfFactory.createStatement(
                    propertySubject,
                    SHACL.IN,
                    typeInSubject
                )
            )
        )
        return shacl
    }

    /**
     * Returns a mapping of class IRIs to SHACL shape statements for the type
     */
    private fun typeToSHACL(type: GraphQLInputObjectType): Pair<List<IRI>, List<Statement>> {
        val subject = rdfFactory.createIRI("$shapeDocBaseIri${type.name}")
        val targetClass = rdfFactory.createIRI(
            (resolveNameAsIri(type.name)
                ?: run {
                    // Or else get IRI from class directive
                    type.getDirectiveArg<StringValue>(DIRECTIVE_CLASS_NAME, ARG_IRI_NAME)?.value?.let {
                        JsonLdHelper.getFQName(it, context) ?: it
                    }
                } ?: throw IllegalArgumentException("No semantic context found for input type '${type.name}'"))
        )
        val fieldStatements = type.fieldDefinitions.filterNot { it.name == FIELD_ID_NAME }.flatMap { fieldToSHACL(it) }
        val ignoredPropertiesListID = rdfFactory.createBNode()
        val collectionNodes = mutableListOf<Statement>()
        RDFCollections.asRDF(listOf(RDF.TYPE), ignoredPropertiesListID, collectionNodes)
        return listOf(subject) to listOfNotNull(
            rdfFactory.createStatement(subject, RDF.TYPE, SHACL.NODE_SHAPE),
            rdfFactory.createStatement(subject, SHACL.TARGET_CLASS, targetClass),
            rdfFactory.createStatement(subject, SHACL.CLOSED, rdfFactory.createLiteral(true)),
            *collectionNodes.toTypedArray(),
            rdfFactory.createStatement(subject, SHACL.IGNORED_PROPERTIES, ignoredPropertiesListID),
            if (type.hasAppliedDirective(DIRECTIVE_CLASS_NAME)) rdfFactory.createStatement(
                subject,
                RDF.TYPE,
                RDFS.CLASS
            ) else null
        ).plus(fieldStatements.map { it.subject }.distinct().map { fieldSubject ->
            rdfFactory.createStatement(
                subject,
                SHACL.PROPERTY,
                fieldSubject
            )
        }).plus(fieldStatements)
    }

    private fun fieldToSHACL(field: GraphQLInputObjectField): List<Statement> {
        val propertyName = resolveNameAsIri(field.name) ?: run {
            // Or else get IRI from predicate directive
            field.getDirectiveArg<StringValue>(DIRECTIVE_PREDICATE_NAME, ARG_IRI_NAME)?.value
        }
        val subject = rdfFactory.createBNode()
        return listOfNotNull(
            rdfFactory.createStatement(subject, RDF.TYPE, SHACL.PROPERTY_SHAPE),
            rdfFactory.createStatement(subject, SHACL.PATH, rdfFactory.createIRI(propertyName)),
            field.name.takeIf { resolveNameAsIri(it) == null }
                ?.let { rdfFactory.createStatement(subject, SHACL.NAME, rdfFactory.createLiteral(it)) },
            rdfFactory.createStatement(
                subject,
                SHACL.MIN_COUNT,
                rdfFactory.createLiteral(
                    (field.getDirectiveArg<IntValue>(DIRECTIVE_SHAPE_NAME, ARG_MIN_COUNT_NAME)?.value?.intValueExact())
                        ?: (if (field.type.isOptional()) 0 else 1)
                )
            ),
            if (!field.type.isList()) rdfFactory.createStatement(
                subject,
                SHACL.MAX_COUNT,
                rdfFactory.createLiteral(1)
            ) else if (field.getAppliedDirective(DIRECTIVE_SHAPE_NAME)?.getArgument(ARG_MAX_COUNT_NAME)
                    ?.getValue<Int?>() != null
            ) rdfFactory.createStatement(
                subject,
                SHACL.MAX_COUNT,
                rdfFactory.createLiteral(
                    field.getAppliedDirective(DIRECTIVE_SHAPE_NAME).getArgument(ARG_MAX_COUNT_NAME).getValue<Int>()
                )
            ) else null,
            if (field.type.isScalar()) {
                rdfFactory.createStatement(
                    subject,
                    SHACL.DATATYPE,
                    rdfFactory.createIRI(field.type.innerType<GraphQLScalarType>().rdfDatatype())
                )
            } else {
                val relType = field.type.innerType<GraphQLInputObjectType>()
                val typeClass = relType.getAppliedDirective(DIRECTIVE_CLASS_NAME)?.let {
                    resolveNameAsIri(relType.name) ?: it.getArgument(ARG_IRI_NAME)?.getValue<String>()
                }
                if (typeClass == null) {
                    // GraphQL describes a Shape
                    val shapeFQName = resolveNameAsIri(relType.name) ?: "$shapeDocBaseIri${relType.name}"
                    rdfFactory.createStatement(subject, SHACL.NODE, rdfFactory.createIRI(shapeFQName))
                } else {
                    // GraphQL describes an actual RDF class
                    rdfFactory.createStatement(subject, SHACL.CLASS, rdfFactory.createIRI(typeClass))
                }
            },
            field.getAppliedDirective(DIRECTIVE_SHAPE_NAME)?.getArgument(ARG_PATTERN_NAME)?.getValue<String?>()?.let {
                rdfFactory.createStatement(subject, SHACL.PATTERN, rdfFactory.createLiteral(it))
            },
            field.getAppliedDirective(DIRECTIVE_SHAPE_NAME)?.getArgument(ARG_MIN_INCLUSIVE_NAME)?.getValue<String?>()
                ?.let {
                    rdfFactory.createStatement(
                        subject,
                        SHACL.MIN_INCLUSIVE,
                        rdfFactory.createLiteral(it, XSD.INTEGER)
                    )
                },
            field.getAppliedDirective(DIRECTIVE_SHAPE_NAME)?.getArgument(ARG_MAX_INCLUSIVE_NAME)?.getValue<String?>()
                ?.let {
                    rdfFactory.createStatement(
                        subject,
                        SHACL.MAX_INCLUSIVE,
                        rdfFactory.createLiteral(it, XSD.INTEGER)
                    )
                },
            field.getAppliedDirective(DIRECTIVE_SHAPE_NAME)?.getArgument(ARG_MIN_EXCLUSIVE_NAME)?.getValue<String?>()
                ?.let {
                    rdfFactory.createStatement(
                        subject,
                        SHACL.MIN_EXCLUSIVE,
                        rdfFactory.createLiteral(it, XSD.INTEGER)
                    )
                },
            field.getAppliedDirective(DIRECTIVE_SHAPE_NAME)?.getArgument(ARG_MAX_EXCLUSIVE_NAME)?.getValue<String?>()
                ?.let {
                    rdfFactory.createStatement(
                        subject,
                        SHACL.MAX_EXCLUSIVE,
                        rdfFactory.createLiteral(it, XSD.INTEGER)
                    )
                },
            field.getAppliedDirective(DIRECTIVE_SHAPE_NAME)?.getArgument(ARG_HAS_VALUE_NAME)?.getValue<String?>()?.let {
                if (field.type.isScalar()) {
                    rdfFactory.createStatement(
                        subject,
                        SHACL.HAS_VALUE,
                        rdfFactory.createLiteral(
                            it,
                            rdfFactory.createIRI(field.type.innerType<GraphQLScalarType>().rdfDatatype())
                        )
                    )
                } else {
                    rdfFactory.createStatement(subject, SHACL.HAS_VALUE, rdfFactory.createIRI(resolveNameAsIri(it)))
                }
            },
            field.getAppliedDirective(DIRECTIVE_SHAPE_NAME)?.getArgument(ARG_MIN_LENGTH_NAME)?.getValue<Int>()?.let {
                rdfFactory.createStatement(
                    subject,
                    SHACL.MIN_LENGTH,
                    rdfFactory.createLiteral(it.toString(), XSD.INTEGER)
                )
            },
            field.getAppliedDirective(DIRECTIVE_SHAPE_NAME)?.getArgument(ARG_MAX_LENGTH_NAME)?.getValue<Int>()?.let {
                rdfFactory.createStatement(
                    subject,
                    SHACL.MAX_LENGTH,
                    rdfFactory.createLiteral(it.toString(), XSD.INTEGER)
                )
            },
            *field.getAppliedDirective(DIRECTIVE_SHAPE_NAME)?.getArgument(ARG_IN_NAME)?.takeIf { it.hasSetValue() }
                ?.let { inArg ->
                    val inValues = inArg.getValue<List<String>>()
                    inValues.map {
                        if (field.type.isScalar()) {
                            rdfFactory.createStatement(
                                subject,
                                SHACL.IN,
                                rdfFactory.createLiteral(
                                    it,
                                    rdfFactory.createIRI(field.type.innerType<GraphQLScalarType>().rdfDatatype())
                                )
                            )
                        } else {
                            rdfFactory.createStatement(subject, SHACL.IN, rdfFactory.createIRI(resolveNameAsIri(it)))
                        }
                    }
                }?.toTypedArray() ?: emptyArray()
        )
    }

    private fun resolveNameAsIri(
        name: String,
        separator: String = KvasirNodeVisitor.GRAPHQL_NAME_PREFIX_SEPARATOR
    ): String? {
        return context[name]?.toString() ?: name.takeIf { it.contains(separator) }?.let { prefixedName ->
            val (prefix, localName) = prefixedName.split(separator)
            context[prefix]?.let { prefixIri ->
                "$prefixIri$localName"
            }
        }
    }

}