package kvasir.utils.test.commons

import kvasir.definitions.rdf.JsonLdKeywords
import org.ajbrown.namemachine.NameGenerator
import java.util.*

object TestDataGenerator {

    val nameGenerator = NameGenerator()

    fun generatePersonData(amount: Int): List<MutableMap<String, Any>> {
        return (0 until amount).map {
            val personId = "${ExampleVocab.baseUri}${UUID.randomUUID()}"
            val personName = nameGenerator.generateName()
            val username = "${personName.firstName}.${personName.lastName}".lowercase()
            mutableMapOf(
                JsonLdKeywords.id to personId,
                JsonLdKeywords.type to ExampleVocab.Person,
                SchemaVocab.givenName to personName.firstName,
                SchemaVocab.familyName to personName.lastName,
                SchemaVocab.email to listOf("$username@somemail.com", "$username@example.org")
            )
        }
    }

}