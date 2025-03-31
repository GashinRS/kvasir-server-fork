package kvasir.utils.test.commons

import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.RDFSVocab
import kvasir.definitions.rdf.SAREFVocab
import org.ajbrown.namemachine.NameGenerator
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.random.Random

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

    fun generateTimeseriesData(nrOfSensors: Int, recordsPerSensor: Int): TimeseriesData {
        val series = (0 until nrOfSensors).map {
            val personName = nameGenerator.generateName()
            mutableMapOf<String, Any>(
                JsonLdKeywords.id to "${ExampleVocab.baseUri}${UUID.randomUUID()}",
                JsonLdKeywords.type to SAREFVocab.Sensor,
                RDFSVocab.label to "${personName.firstName}'s temperature sensor."
            )
        }
        val startTs = Instant.now().minusSeconds(recordsPerSensor.toLong())
        val recordsBySensorId = series.map { it[JsonLdKeywords.id]!! as String }.map { sensorId ->
            sensorId to (0 until recordsPerSensor).map { index ->
                mutableMapOf(
                    JsonLdKeywords.id to "${sensorId}_Observation$index",
                    JsonLdKeywords.type to SAREFVocab.Measurement,
                    SAREFVocab.measurementMadeBy to mapOf(JsonLdKeywords.id to sensorId),
                    SAREFVocab.hasTimestamp to startTs.plusSeconds(index.toLong()).truncatedTo(ChronoUnit.MICROS)
                        .toString(),
                    // Using Integer here, to avoid issues with rounding errors in tests
                    SAREFVocab.hasValue to Random.nextInt(35, 39)
                )
            }
        }.toMap()
        return TimeseriesData(series, recordsBySensorId)
    }

}

data class TimeseriesData(
    val sensors: List<Map<String, Any>>,
    val recordsBySensorId: Map<String, List<Map<String, Any>>>
) {
    fun getAllJsonLD(): List<Map<String, Any>> {
        return sensors + (recordsBySensorId.flatMap { it.value })
    }
}