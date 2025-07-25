package kvasir.services.api.kg.query

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.http.TestHTTPEndpoint
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import kvasir.definitions.kg.ChangeRequest
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.definitions.kg.graphql.FIELD_ID_NAME
import kvasir.definitions.rdf.*
import kvasir.utils.idgen.ChangeRequestId
import kvasir.utils.test.clickhouse.ClickhouseTestResource
import kvasir.utils.test.commons.TestConstants
import kvasir.utils.test.commons.TestDataGenerator
import kvasir.utils.test.commons.TestHelpers
import kvasir.utils.test.commons.TimeseriesData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

@QuarkusTest
@TestHTTPEndpoint(QueryApi::class)
@QuarkusTestResource(ClickhouseTestResource::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class QueryApiSpecializedStorageTest {

    @Inject
    lateinit var kg: KnowledgeGraph

    @Inject
    lateinit var testHelpers: TestHelpers


    lateinit var podUri: String
    lateinit var sensorData: TimeseriesData

    @BeforeAll
    fun populateData() {
        podUri = testHelpers.getPodUri(TestConstants.TEST_POD_3_ID)
        sensorData = TestDataGenerator.generateTimeseriesData(3, 1000)
        kg.process(
            ChangeRequest(
                ChangeRequestId.generate("$podUri/changes").encode(),
                emptyMap(),
                podUri,
                insert = sensorData.getAllJsonLD()
            )
        ).await().indefinitely()
    }

    @Test
    @Order(0)
    fun testGetSensorDataViaFilter() {
        val selectedSeries = sensorData.sensors.random()
        val selectedId = selectedSeries[JsonLdKeywords.id] as String
        val q = QueryInputWithContext(
            """
            {
              saref_Measurement(saref_measurementMadeBy: "$selectedId", pageSize: 100, orderBy: "-saref_hasTimestamp") {
                saref_hasTimestamp
                saref_hasValue
                saref_measurementMadeBy {
                  id
                }
              }
            }
        """.trimIndent(), providedContext = TestConstants.CONTEXT
        )
        val result = testHelpers.queryKGViaHTTP(q, podUri)
        println(result)
        assertEquals(
            sensorData.recordsBySensorId[selectedId]!!.sortedByDescending { it[SAREFVocab.hasTimestamp]!! as Comparable<Any> }
                .take(100).map { it[SAREFVocab.hasTimestamp] to it[SAREFVocab.hasValue] },
            result.data!!.getJsonArray<JSONObject>("saref_Measurement")!!
                .map {
                    it.getJsonArray<String>("saref_hasTimestamp")!!.first() to it.getJsonArray<Any>("saref_hasValue")!!
                        .first()
                }
        )
    }

    @Test
    @Order(1)
    fun testGetSensorDataViaReverseRelation() {
        val selectedSeries = sensorData.sensors.random()
        val selectedId = selectedSeries[JsonLdKeywords.id] as String
        val q = QueryInputWithContext(
            """
            {
              saref_Sensor(id: "$selectedId") {
                id
                rdfs_label
                hasMeasurement(pageSize: 25, orderBy: "saref_hasTimestamp") {
                  id
                  saref_hasTimestamp
                  saref_hasValue
                }
              }
            }
        """.trimIndent(), providedContext = TestConstants.CONTEXT
        )
        val result = testHelpers.queryKGViaHTTP(q, podUri)

        val retrievedSensor = result.data!!.getJsonArray<JSONObject>("saref_Sensor")!!.first()
        assertEquals(selectedId, retrievedSensor[FIELD_ID_NAME])
        assertEquals(listOf(selectedSeries[RDFSVocab.label]), retrievedSensor["rdfs_label"])
        assertEquals(
            sensorData.recordsBySensorId[selectedId]!!.sortedBy { it[SAREFVocab.hasTimestamp]!! as Comparable<Any> }
                .take(25).map { it[SAREFVocab.hasTimestamp] to it[SAREFVocab.hasValue] },
            retrievedSensor.getJsonArray<JSONObject>("hasMeasurement")!!
                .map {
                    it.getJsonArray<String>("saref_hasTimestamp")!!.first() to it.getJsonArray<Any>("saref_hasValue")!!
                        .first()
                }
        )
    }

}