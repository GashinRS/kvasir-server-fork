package kvasir.utils.kg

import graphql.language.AstTransformer
import graphql.schema.idl.SchemaParser

object SchemaValidator {

    fun validateSchema(schema: String, context: Map<String, Any>) {
        val parsedSchema = SchemaParser().parse(schema)
        parsedSchema.addKvasirDirectives()
        val checkContextVisitor = CheckContextVisitor(context)
        parsedSchema.types().forEach { (_, type) ->
            AstTransformer().transform(type, checkContextVisitor)
        }
    }

}