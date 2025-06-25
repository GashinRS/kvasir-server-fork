package kvasir.plugins.http.common.extensions

import io.quarkus.smallrye.openapi.OpenApiFilter
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import org.eclipse.microprofile.openapi.OASFactory
import org.eclipse.microprofile.openapi.OASFilter
import org.eclipse.microprofile.openapi.models.OpenAPI
import org.eclipse.microprofile.openapi.models.media.Schema
import org.jboss.logging.Logger

@OpenApiFilter(OpenApiFilter.RunStage.BUILD)
class OpenApiJsonLDFilter : OASFilter {
    private val log = Logger.getLogger(OpenApiJsonLDFilter::class.java)
    private val replacerMap = mapOf(
        Pair("https://kvasir.discover.ilabt.imec.be/vocab", "kss")
    )
    private val atContext = JsonObject()
        .put("ex", "http://example.org/")
        .apply { replacerMap.entries.forEach { entry -> this.put(entry.value, entry.key) } }

    override fun filterOpenAPI(openAPI: OpenAPI) {
        log.info("Filtering and compacting OpenAPI Schema...")
        // Compact all component type definitions
        openAPI.components.schemas(
            openAPI.components.schemas
                .map { Pair(it.key, compactSchema(it.value)) }
                .toMap()
        )

        openAPI.components.schemas
            // Only check schemas that have an @context property already
            .filterValues { it.properties?.containsKey("@context") ?: false }
            // Map each schema to its own properties schemas for ARRAY types, if properties is present
            .flatMap {
                it.value.properties?.filterValues { subScheme ->
                    (subScheme.type?.contains(Schema.SchemaType.ARRAY) ?: false)
                }?.values ?: emptyList()
            }
            // Set each array type properties ref to a new GraphItem affixed ref (that does not exist yet)
            // Map the original refs
            .mapNotNull {
                val ref = it.items.ref;
                if (ref != null) {
                    it.items.ref(ref + "GraphItem");
                }
                ref;
            }
//            .filter { !it.endsWith("GraphItem") }
            // Create new types without @context from the original refs
            .forEach {
                val name = it.substringAfterLast("/")
                log.info("REF NAME: $name")
                val newName = name+"GraphItem"
                val copy = openAPI.components.schemas.get(name)
                // Only if it does not exist yet
                if (!openAPI.components.schemas.contains(newName)) {
                    // Create the new schema
                    val newComponent = openAPI.components.addSchema(newName, copy)
                    // Remove all occurrences of @context from it and its descendants
                    removeAtContextRecursively(newComponent.schemas.get(newName)!!, newName)
                }
            }
    }

    private fun removeAtContextRecursively(schema: Schema, logName: String?) {
        log.infof("RECURSIVE REMOVE WITH %s", logName)
        schema.removeProperty("@context")
        // Rewrite ref for arrays
        schema.properties.values
            .filter { it.type?.contains(Schema.SchemaType.ARRAY) ?: false }
            .forEach {
                it.items?.ref?.let { ref -> if (!ref.endsWith("GraphItem")) it.items.ref(ref + "GraphItem") }
            }
        // Recursive for objects
        schema.properties
            .filter { it.value.type?.contains(Schema.SchemaType.OBJECT) ?: false }
            .forEach { removeAtContextRecursively(it.value, it.key ) }

    }

    private fun compactSchema(schema: Schema): Schema {
        if (schema.required != null) {
            schema.required = schema.required.map(::compact)
        }
        if (schema.enumeration != null) {
            schema.enumeration = schema.enumeration.map { obj -> obj.toString() }.map(::compact)
        }
        if (schema.example != null) {
            schema.example = compact(schema.example.toString())
        }
        if (schema.examples != null) {
            schema.examples = schema.examples.map { obj -> obj.toString() }.map(::compact)
        }
        if (schema.properties != null) {
            schema.properties = addContextAndSamples(
                schema.properties
                    .map { entry -> Pair(compact(entry.key), compactSchema(entry.value)) }
                    .toMap().toMutableMap())
        }
        return schema;
    }

    private fun compact(input: String): String {
        replacerMap.forEach { (key, value) ->
            if (input.startsWith(key)) {
                return input.replaceFirst(key + "#", value + ":")
            }
        }
        return input
    }

    private fun addContextAndSamples(map: MutableMap<String, Schema>): MutableMap<String, Schema> {
        // If @context exists or an @graph property is present
        if (map.containsKey("@graph") || map.containsKey("@context") || map.keys.any { propKey ->
                replacerMap.values.any { replKey ->
                    propKey.startsWith(
                        replKey + ":"
                    )
                }
            }) {
            // Add the @context example or create a new @context property
            map.merge(
                "@context",
                OASFactory.createSchema()
                    .addType(Schema.SchemaType.OBJECT)
                    .addExample(atContext.map),
                { key, value ->
                    value.example = null;
                    if (value.examples.isEmpty()) {
                        value.addExample(atContext.map)
                    }
                    value
                });
        }
        return map
    }

}