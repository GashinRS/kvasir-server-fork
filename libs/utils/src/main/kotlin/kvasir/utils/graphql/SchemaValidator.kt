package kvasir.utils.graphql

import graphql.language.AstTransformer
import graphql.schema.idl.SchemaParser
import kvasir.definitions.rdf.JSONObject

object SchemaValidator {

    fun validateSchema(schema: String, context: JSONObject) {
        val parsedSchema = SliceGraphQLSchema(schema, context).getTypeDefinitionRegistry()
        val checkContextVisitor = CheckContextVisitor(context)
        parsedSchema.types().forEach { (_, type) ->
            AstTransformer().transform(type, checkContextVisitor)
        }
    }

}