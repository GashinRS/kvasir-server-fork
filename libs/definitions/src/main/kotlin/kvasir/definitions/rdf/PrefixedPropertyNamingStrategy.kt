package kvasir.definitions.rdf

import com.fasterxml.jackson.databind.PropertyNamingStrategy
import com.fasterxml.jackson.databind.cfg.MapperConfig
import com.fasterxml.jackson.databind.introspect.AnnotatedField
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod
import com.fasterxml.jackson.databind.introspect.AnnotatedParameter

/**
 * A Jackson PropertyNamingStrategy that adds a URI prefix to all property names,
 * except for specific reserved names which are prefixed with "@".
 * Intended for use with JSON-LD serialization.
 */
class PrefixedPropertyNamingStrategy(val prefix: String) : PropertyNamingStrategy() {

    override fun nameForConstructorParameter(
        config: MapperConfig<*>,
        ctorParam: AnnotatedParameter,
        defaultName: String
    ): String {
        return nameTransform(defaultName)
    }

    override fun nameForField(config: MapperConfig<*>, field: AnnotatedField, defaultName: String): String {
        return nameTransform(defaultName)
    }

    override fun nameForGetterMethod(
        config: MapperConfig<*>,
        method: AnnotatedMethod,
        defaultName: String
    ): String? {
        return nameTransform(defaultName)
    }

    override fun nameForSetterMethod(
        config: MapperConfig<*>,
        method: AnnotatedMethod,
        defaultName: String
    ): String {
        return nameTransform(defaultName)
    }

    private fun nameTransform(name: String): String {
        return when (name) {
            "id", "context", "graph", "type" -> "@$name"
            else -> "$prefix$name"
        }
    }

}