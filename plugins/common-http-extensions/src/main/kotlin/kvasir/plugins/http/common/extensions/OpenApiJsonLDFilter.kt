package kvasir.plugins.http.common.extensions

import io.quarkus.smallrye.openapi.OpenApiFilter
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

        // Find all types with @graph and duplicate their refs with new component type (WithoutContext)
        val refs = openAPI.components.schemas.mapNotNull {
            val ref = it.value.properties?.get("@graph")?.items?.ref
            if (ref != null) {
                it.value.properties.get("@graph")!!.items.ref(ref + "GraphItem");
            }
            ref;
        }

        // Create new types without @context from refs list
        refs.forEach {
            val name = it.substringAfterLast("/")
            val copy = openAPI.components.schemas.get(name)
            copy!!.removeProperty("@context")
            openAPI.components.addSchema(name + "GraphItem", copy)
        }
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
            schema.properties = markupContextMap(
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

    private fun markupContextMap(map: MutableMap<String, Schema>): MutableMap<String, Schema> {
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