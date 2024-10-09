package kvasir.utils.graphql2shacl

import com.github.jsonldjava.utils.JsonUtils
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.utils.graphql.innerType
import kvasir.utils.graphql.isList
import kvasir.utils.graphql.isOptional
import kvasir.utils.graphql.isScalar
import kvasir.utils.graphql.rdfDatatype
import kvasir.utils.kg.KvasirNodeVisitor.Companion.GRAPHQL_NAME_PREFIX_SEPARATOR
import kvasir.utils.kg.addKvasirDirectives
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.impl.DynamicModelFactory
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.model.util.RDFCollections
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.eclipse.rdf4j.model.vocabulary.RDFS
import org.eclipse.rdf4j.model.vocabulary.SHACL
import org.eclipse.rdf4j.model.vocabulary.XSD
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.Rio
import org.eclipse.rdf4j.rio.WriterConfig
import org.eclipse.rdf4j.rio.helpers.BasicWriterSettings
import java.io.StringWriter
import java.util.UUID


class GraphQL2SHACL(graphql: String, private val context: Map<String, Any>) {

    private val typeRegistry = SchemaParser().parse(graphql)
    private val rdfFactory = SimpleValueFactory.getInstance()
    private val shapeDocBaseIri = "kvasir:shapes:${UUID.randomUUID()}:"

    fun toSHACL(): Model {
        typeRegistry.addKvasirDirectives()
        val graphQLSchema =
            SchemaGenerator().makeExecutableSchema(typeRegistry, RuntimeWiring.newRuntimeWiring().build())
        val statements = graphQLSchema.allTypesAsList.filterIsInstance<GraphQLObjectType>()
            .filterNot { it.name in setOf("Query", "Mutation", "Subscription") || it.name.startsWith("__") }
            .flatMap { typeToSHACL(it) }

        val model = DynamicModelFactory().createEmptyModel()
        model.addAll(statements)
        context.plus(SHACL.PREFIX to SHACL.NAMESPACE).forEach { (prefix, namespace) ->
            model.setNamespace(prefix, namespace.toString())
        }
        return model
    }

    fun getFilterShape(shacl: Model, targetSubjects: List<String>, allowedTypes: List<String>): Model {
        val availableTypes = allowedTypes.map { rdfFactory.createIRI(it) }
        val filterNodeShapeSubject = rdfFactory.createIRI("${shapeDocBaseIri}FilterShape")
        val typeInSubject = rdfFactory.createBNode()
        val propertySubject = rdfFactory.createBNode()
        RDFCollections.asRDF(availableTypes.toMutableList(), typeInSubject, shacl)
        shacl.addAll(
            listOf(
                rdfFactory.createStatement(
                    filterNodeShapeSubject,
                    RDF.TYPE,
                    SHACL.NODE_SHAPE
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
                ),
            ) +
                    targetSubjects.map { targetSubject ->
                        rdfFactory.createStatement(
                            filterNodeShapeSubject,
                            SHACL.TARGET_NODE,
                            rdfFactory.createIRI(targetSubject)
                        )
                    }
        )
        return shacl
    }

    private fun typeToSHACL(type: GraphQLObjectType): List<Statement> {
        val subject = rdfFactory.createIRI(
            (resolveNameAsIri(type.name)
                ?: run {
                    // Or else get IRI from class directive
                    type.getAppliedDirective("class")?.getArgument("iri")?.getValue<String>()
                })
                ?: "$shapeDocBaseIri${type.name}"
        )
        val fieldStatements = type.fieldDefinitions.filterNot { it.name == "id" }.flatMap { fieldToSHACL(it) }
        val ignoredPropertiesListID = rdfFactory.createBNode()
        val collectionNodes = mutableListOf<Statement>()
        RDFCollections.asRDF(listOf(RDF.TYPE), ignoredPropertiesListID, collectionNodes)
        return listOfNotNull(
            rdfFactory.createStatement(subject, RDF.TYPE, SHACL.NODE_SHAPE),
            rdfFactory.createStatement(subject, SHACL.CLOSED, rdfFactory.createLiteral(true)),
            *collectionNodes.toTypedArray(),
            rdfFactory.createStatement(subject, SHACL.IGNORED_PROPERTIES, ignoredPropertiesListID),
            if (type.hasAppliedDirective("class")) rdfFactory.createStatement(subject, RDF.TYPE, RDFS.CLASS) else null
        ).plus(fieldStatements.map { it.subject }.distinct().map { fieldSubject ->
            rdfFactory.createStatement(
                subject,
                SHACL.PROPERTY,
                fieldSubject
            )
        }).plus(fieldStatements)
    }

    private fun fieldToSHACL(field: GraphQLFieldDefinition): List<Statement> {
        val propertyName = resolveNameAsIri(field.name) ?: run {
            // Or else get IRI from predicate directive
            field.getAppliedDirective("predicate")?.getArgument("iri")?.getValue<String>()
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
                    field.getAppliedDirective("shape")?.getArgument("minCount")?.getValue<Int?>()
                        ?: (if (field.type.isOptional()) 0 else 1)
                )
            ),
            if (!field.type.isList()) rdfFactory.createStatement(
                subject,
                SHACL.MAX_COUNT,
                rdfFactory.createLiteral(1)
            ) else if (field.getAppliedDirective("shape")?.getArgument("maxCount") != null) rdfFactory.createStatement(
                subject,
                SHACL.MAX_COUNT,
                rdfFactory.createLiteral(field.getAppliedDirective("shape").getArgument("maxCount").getValue<Int>())
            ) else null,
            if (field.type.isScalar()) {
                rdfFactory.createStatement(
                    subject,
                    SHACL.DATATYPE,
                    rdfFactory.createIRI(field.type.innerType<GraphQLScalarType>().rdfDatatype())
                )
            } else {
                val relType = field.type.innerType<GraphQLObjectType>()
                val typeClass = relType.getAppliedDirective("class")?.let {
                    resolveNameAsIri(relType.name) ?: it.getArgument("iri")?.getValue<String>()
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
            field.getAppliedDirective("shape")?.getArgument("pattern")?.getValue<String?>()?.let {
                rdfFactory.createStatement(subject, SHACL.PATTERN, rdfFactory.createLiteral(it))
            },
            field.getAppliedDirective("shape")?.getArgument("minInclusive")?.getValue<String?>()?.let {
                rdfFactory.createStatement(
                    subject,
                    SHACL.MIN_INCLUSIVE,
                    rdfFactory.createLiteral(it, XSD.INTEGER)
                )
            },
            field.getAppliedDirective("shape")?.getArgument("maxInclusive")?.getValue<String?>()?.let {
                rdfFactory.createStatement(
                    subject,
                    SHACL.MAX_INCLUSIVE,
                    rdfFactory.createLiteral(it, XSD.INTEGER)
                )
            },
            field.getAppliedDirective("shape")?.getArgument("hasValue")?.getValue<String?>()?.let {
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
            *field.getAppliedDirective("shape")?.getArgument("in")?.takeIf { it.hasSetValue() }?.let { inArg ->
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

    private fun resolveNameAsIri(name: String, separator: String = GRAPHQL_NAME_PREFIX_SEPARATOR): String? {
        return context[name]?.toString() ?: name.takeIf { it.contains(separator) }?.let { prefixedName ->
            val (prefix, localName) = prefixedName.split(separator)
            context[prefix]?.let { prefixIri ->
                "$prefixIri$localName"
            }
        }
    }

}

fun main() {
    val context = mapOf(
        "ex" to "http://example.org/",
        "schema" to "http://schema.org/"
    )
    val graphql = """
        type Query {
          humans: [ex_Human!]!
          human(id: ID!): ex_Human
        }  
        
        type ex_Human @class {
          id: ID!
          schema_givenName: String! @shape(pattern: "^Bob.*$")
          schema_familyName: String!
          schema_age: Int! @shape(minInclusive: "18", maxInclusive: "120")
        }
        
        type ex_Cat @class {
          id: ID!
          schema_givenName: String! @shape(hasValue: "Risto")
        }
    """.trimIndent()
    val jsonLdInput = """
        {
          "@context" : {
            "schema" : "http://schema.org/",
            "ex" : "http://example.org/"
          },
            "@graph" : [ {
                "@id" : "ex:risto",
                "@type" : "ex:Cat",
                "schema:givenName" : "Risto"
            }, {
                "@id" : "ex:bob",
                "@type" : "ex:Human",
                "schema:givenName" : "Bob",
                "schema:familyName" : "Smith",
                "schema:age" : 19
            }, {
                "@id" : "ex:alice",
                "@type" : "ex:Human",
                "schema:givenName" : "Alice",
                "schema:familyName" : "Smith",
                "schema:age" : 17
            }]
        }
    """.trimIndent()
    val graphQL2SHACL = GraphQL2SHACL(graphql, context)

    val jsonLdInstances =
        JsonLdHelper.toCompactFQForm(JsonUtils.fromString(jsonLdInput) as Map<String, Any>)["@graph"]!!.let { it as List<Map<String, Any>> }

    println(jsonLdInstances.filter {
        val shapes = graphQL2SHACL.getFilterShape(
            graphQL2SHACL.toSHACL(),
            listOf(it["@id"] as String),
            listOf("http://example.org/Human", "http://example.org/Cat")
        )
        val instanceStr = StringWriter().use { writer ->
            JsonUtils.write(writer, it)
            writer.toString()
        }
        SHACLValidator.filter(shapes, instanceStr)
    }
    )
}

fun printModel(model: Model): String {
    return StringWriter().use { writer ->
        val config = WriterConfig().set(BasicWriterSettings.PRETTY_PRINT, true)
            .set(BasicWriterSettings.INLINE_BLANK_NODES, true)
        Rio.write(model, writer, RDFFormat.TURTLE, config)
        writer.toString()
    }
}