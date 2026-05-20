package kvasir.plugins.http.common.extensions

import io.quarkus.smallrye.openapi.OpenApiFilter
import io.vertx.core.json.JsonObject
import kvasir.definitions.rdf.KvasirVocab
import org.eclipse.microprofile.openapi.OASFactory
import org.eclipse.microprofile.openapi.OASFilter
import org.eclipse.microprofile.openapi.models.OpenAPI
import org.eclipse.microprofile.openapi.models.media.Schema
import org.jboss.logging.Logger

const val JSONLD_MIME_TYPE = "application/ld+json"
const val TEXT_EVENT_STREAM_MIME_TYPE = "text/event-stream"

@OpenApiFilter(OpenApiFilter.RunStage.BUILD)
class OpenApiJsonLDFilter : OASFilter {
    private val log = Logger.getLogger(OpenApiJsonLDFilter::class.java)
    private val KVASIR_VOCAB_FQN = KvasirVocab.baseUri.trimEnd('#')
    private val KVASIR_VOCAB_PREFIX = "kss"
    private val AT_CONTEX_KEY = "@context"
    private val AT_GRAPH_KEY = "@graph"
    private val AT_TYPE_KEY = "@type"
    private val AT_ID_KEY = "@id"
    private val JSONLD_RESERVED_KEYS = listOf(AT_CONTEX_KEY, AT_GRAPH_KEY, AT_ID_KEY, AT_TYPE_KEY);
    private val GRAPH_ITEM_TYPE_SUFFIX = "__GraphItem"
    private val replacerMap = mapOf(
        Pair(KVASIR_VOCAB_FQN, KVASIR_VOCAB_PREFIX)
    )
    private val atContextObj = JsonObject()
        .put("ex", "http://example.org/")
        .apply { replacerMap.entries.forEach { entry -> this.put(entry.value, entry.key) } }

    override fun filterOpenAPI(openAPI: OpenAPI) {
        // 1. Gather all toplevel Return refs and Request refs
        val responseRefs = emptySet<String>().toMutableSet();
        val responseArraySchemas = emptySet<Schema>().toMutableSet();
        val requestRefs = emptySet<String>().toMutableSet();
        val requestArraySchemas = emptySet<Schema>().toMutableSet();
        openAPI.paths.pathItems.forEach {

            // TODO: Add support for text/event-stream

            it.value.operations.forEach { op ->
                // Get all refs in the return type that are object references
                val respRefs = op.value.responses?.apiResponses
                    ?.flatMap { resp ->
                        val content = resp.value.content ?: return@flatMap emptyList()
                        listOfNotNull(
                            content.getMediaType(JSONLD_MIME_TYPE)?.schema?.ref,
                            content.getMediaType(TEXT_EVENT_STREAM_MIME_TYPE)?.schema?.ref,
                        )
                    }
                    ?: emptyList()
                responseRefs.addAll(respRefs);

                // Get all schemas in the return type that are of type array
                val arraySchemas = op.value.responses?.apiResponses
                    ?.map { resp -> resp.value.content?.getMediaType(JSONLD_MIME_TYPE)?.schema }
                    ?.filterNotNull()
                    ?.filter { schema -> schema.type?.contains(Schema.SchemaType.ARRAY) ?: false }
                    ?: emptyList()
                responseArraySchemas.addAll(arraySchemas);

                // Get the request body ref that are object references
                op.value.requestBody?.content?.getMediaType(JSONLD_MIME_TYPE)?.schema?.ref?.let { reqRef ->
                    requestRefs.add(reqRef)
                }

                // Get the request body schemas that are array types
                op.value.requestBody?.content?.getMediaType(JSONLD_MIME_TYPE)?.schema?.let { reqSchema ->
                    if (reqSchema.type?.contains(Schema.SchemaType.ARRAY) ?: false) {
                        requestArraySchemas.add(reqSchema)
                    }
                }
            }
        }


        log.tracef("Response entrypoints: \n%s", responseRefs.joinToString(",\n "))
        log.tracef("Request entrypoints: \n%s", requestRefs.joinToString(",\n"))

        // 2. Fix the return array schemas by wrapping them in an object with @graph property and adding @context property
        fixArraySchemas(openAPI, responseArraySchemas);

        // 3. Fix the request body array schemas by wrapping them in an object with @graph property and adding @context property
        fixArraySchemas(openAPI, requestArraySchemas);

        // 4. Fix the return type refs
        fixReferences(openAPI, responseRefs)

        // 5. Fix the request body refs
        fixReferences(openAPI, requestRefs)

    }

    /**
     * Deep copy the given schema.
     */
    private fun deepCopySchema(schema: Schema): Schema {
        return OASFactory.createSchema()
            .type(schema.type)
            .required(schema.required)
            .enumeration(schema.enumeration)
            .items(schema.items)
            .uniqueItems(schema.uniqueItems)
            .ref(schema.ref)
            .xml(schema.xml)
            .example(schema.example)
            .examples(schema.examples)
            .title(schema.title)
            .properties(schema.properties?.mapValues { deepCopySchema(it.value) })
    }

    /**
     * Recursively removes all occurrences of @context from the given schema and its descendant properties.
     */
    private fun removeAtContextRecursively(schema: Schema, logName: String?) {
        log.tracef("RECURSIVE REMOVE @context for  %s", logName)

        schema.removeProperty(AT_CONTEX_KEY)
        schema.removeRequired(AT_CONTEX_KEY)

        // Remove all @context recursively from properties too
        schema.properties
            ?.forEach { removeAtContextRecursively(it.value, it.key) }
    }

    /**
     * Add prefixes to all keys and properties in the given schema (recursively)
     */
    private fun fixSchemaPrefixes(openApi: OpenAPI, schema: Schema, logName: String): Schema {
        log.trace("fixSchemaPrefixes: $logName")
        if (schema.required != null) {
            schema.required = schema.required.map(::fixPrefixes)
        }
        if (schema.enumeration != null) {
            schema.enumeration = schema.enumeration.map { obj -> obj.toString() }.map(::fixPrefixes)
        }
        if (schema.properties != null) {
            schema.properties =
                schema.properties.map { prop ->
                    Pair(fixPrefixes(prop.key), fixSchemaPrefixes(openApi, prop.value, prop.key))
                }
                    .toMap().toMutableMap()
        }

        // Also check for any arrays and recursively check their item ref
        if (schema.items != null) {
            // Also fix prefixes of the @graph item
            val ref = schema.items.ref
            if (ref != null) {
                val refSchemaKey = ref.substringAfter("#/components/schemas/")
                val refSchema = openApi.components?.schemas?.get(refSchemaKey)!!
                fixSchemaPrefixes(openApi, refSchema, refSchemaKey);
            }
        }

        return schema;
    }

    /**
     * This will prefix all properties not in JSONLD_RESERVED_KEYS with the KVASIR_VOCAB_PREFIX,
     * if they do not already start with a prefix.
     */
    private fun fixPrefixes(input: String): String {
        if (!input.startsWith("$KVASIR_VOCAB_PREFIX:")) {
            // Special handling for id property
            if (input == "id") {
                return AT_ID_KEY;
            }
            // Special handling for graph property
            if (input == "graph") {
                return AT_GRAPH_KEY;
            }

            // All others: prefix with KVASIR_VOCAB_PREFIX, unless the key matches one of JSONLD_RESERVED_KEYS
            if (!JSONLD_RESERVED_KEYS.contains(input)) {
                return "$KVASIR_VOCAB_PREFIX:$input"
            }
        }
        return input
    }

    /**
     * Fix the given array schemas by wrapping them in an object with @graph property and adding @context property
     * The array items will also have their properties prefixed
     */
    private fun fixArraySchemas(openApi: OpenAPI, arraySchemas: Set<Schema>) {
        arraySchemas.forEach { schema ->
            log.debugf("Fixing array schema for %s", schema.items.ref + "[]")
            val ref = schema.items.ref
            if (ref != null) {
                val atGraphSchema = newAtGraphSchema(ref);

                // Fix the schema of the @graph item as well, in case it has properties that need to be prefixed
                fixSchemaPrefixes(openApi, atGraphSchema, "$ref[]")

                // Set the type to object
                schema.type(listOf(Schema.SchemaType.OBJECT));
                schema.items = null;
                // Add the @graph property

                schema.addProperty(AT_GRAPH_KEY, atGraphSchema)
                // Add the @context property
                schema.addProperty(
                    AT_CONTEX_KEY,
                    newAtContextSchema()
                );
            }
        }
    }

    /**
     * Fix the given refs by adding @context and removing @context if it is an @graph array
     */
    private fun fixReferences(openApi: OpenAPI, refs: Set<String>) {
        refs.forEach { ref ->
            // Get actual schemaKey from ref
            val refSchemaKey = ref.substringAfter("#/components/schemas/")
            // Get actual schema
            val schema = openApi.components?.schemas?.get(refSchemaKey);
            if (schema != null) {
                // 1. Every property must be prefixed with KVASIR_VOCAB_PREFIX, unless the key matches one of JSONLD_RESERVED_KEYS
                // Overwrite the original schema with the fixed one
                fixSchemaPrefixes(openApi, schema, refSchemaKey)

                // 2. If they are of type object, add an @context property
                if (schema.type?.contains(Schema.SchemaType.OBJECT) ?: false) {
                    // If the schema has a property kss:context, remove it first (it was wrongly processed before)
                    if (schema.properties?.containsKey("kss:context") ?: false) {
                        schema.removeProperty("kss:context");
                    }

                    schema.addProperty(
                        AT_CONTEX_KEY,
                        newAtContextSchema()
                    );
                }
            }
        }


        // 3. When reference is used inside an @graph array, a new reference should be made that does not have an
        //    @context and it should reference that instead
        refs.forEach { ref ->
            // Get actual schemaKey from ref
            val refSchemaKey = ref.substringAfter("#/components/schemas/")
            // Get actual schema
            val schema = openApi.components?.schemas?.get(refSchemaKey);
            if (schema != null) {
                // If schema type is object, there is a property @graph and that type is array
                if (schema.type?.contains(Schema.SchemaType.OBJECT) ?: false) {
                    schema.properties?.get(AT_GRAPH_KEY)?.let { graphProp ->
                        if (graphProp.type.contains(Schema.SchemaType.ARRAY)) {
                            // If the items ref is in refs, create a new schema without @context and reference that instead
                            val itemsRef = graphProp.items?.ref
                            if (itemsRef != null && refs.contains(itemsRef)) {
                                val itemsRefSchemaKey = itemsRef.substringAfter("#/components/schemas/")
                                val newSchemaKey = itemsRefSchemaKey + GRAPH_ITEM_TYPE_SUFFIX
                                val newSchema = deepCopySchema(openApi.components?.schemas?.get(itemsRefSchemaKey)!!)

                                // Remove @context from the new schema
                                removeAtContextRecursively(newSchema, newSchemaKey)

                                // Add the newSchema
                                openApi.components.addSchema(newSchemaKey, newSchema)

                                // Fix the ref
                                graphProp.items.ref("#/components/schemas/$newSchemaKey")
                            }
                        }
                    }
                }
            }

        }
    }

    /**
     * Create a new @context schema with descriptions and examples
     */
    private fun newAtContextSchema(): Schema {
        return OASFactory.createSchema()
            .addType(Schema.SchemaType.OBJECT)
            .description("The JSON-LD context for this response/request")
            .addExample(atContextObj.map)
    }

    /**
     * Create a new @graph schema for the given graph item ref
     */
    private fun newAtGraphSchema(graphItemRef: String): Schema {
        return OASFactory.createSchema().addType(Schema.SchemaType.ARRAY)
            .items(OASFactory.createSchema().ref(graphItemRef))
    }
}