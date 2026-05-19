package kvasir.plugins.http.common.extensions

import io.quarkus.smallrye.openapi.OpenApiFilter
import io.vertx.core.json.JsonObject
import kvasir.definitions.rdf.KvasirVocab
import org.eclipse.microprofile.openapi.OASFactory
import org.eclipse.microprofile.openapi.OASFilter
import org.eclipse.microprofile.openapi.models.OpenAPI
import org.eclipse.microprofile.openapi.models.media.Schema
import org.jboss.logging.Logger

@OpenApiFilter(OpenApiFilter.RunStage.BUILD)
class OpenApiJsonLDFilter : OASFilter {
    private val log = Logger.getLogger(OpenApiJsonLDFilter::class.java)
    private val KVASIR_VOCAB_FQN = KvasirVocab.baseUri.trimEnd('#')
    private val KVASIR_VOCAB_PREFIX = "kss"
    private val AT_CONTEX_KEY = "@context"
    private val AT_GRAPH_KEY = "@graph"
    private val AT_ID_KEY = "@id"
    private val JSONLD_RESERVED_KEYS = listOf(AT_CONTEX_KEY, AT_GRAPH_KEY, AT_ID_KEY);
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
        val requestRefs = emptySet<String>().toMutableSet();
        openAPI.paths.pathItems.forEach {
            it.value.operations.forEach { op ->
                val respRefs = op.value.responses?.apiResponses
                    ?.map { resp -> resp.value.content?.getMediaType("application/ld+json")?.schema?.ref }
                    ?.filterNotNull()
                    ?: emptyList()
                responseRefs.addAll(respRefs);
                op.value.requestBody?.content?.getMediaType("application/ld+json")?.schema?.ref?.let { reqRef ->
                    requestRefs.add(reqRef)
                }
            }
        }


        log.tracef("Response entrypoints: \n%s", responseRefs.joinToString(",\n "))
        log.tracef("Request entrypoints: \n%s", requestRefs.joinToString(",\n"))

        // 2. Fix the return type refs
        fixReferences(openAPI, responseRefs)

        // 3. Fix the request body refs
        fixReferences(openAPI, requestRefs)

    }


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
    private fun fixSchemaPrefixes(schema: Schema, logName: String): Schema {
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
                    Pair(fixPrefixes(prop.key), fixSchemaPrefixes(prop.value, prop.key)) }
                    .toMap().toMutableMap()
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
                fixSchemaPrefixes(schema, refSchemaKey)

                // 2. If they are of type object, add an @context property
                if (schema.type?.contains(Schema.SchemaType.OBJECT) ?: false) {
                    // If the schema has a property kss:context, remove it first
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
}