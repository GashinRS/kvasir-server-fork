package kvasir.services.changes
//
//import io.quarkus.logging.Log
//import io.quarkus.test.Mock
//import io.quarkus.test.junit.QuarkusTest
//import io.smallrye.mutiny.Multi
//import io.smallrye.mutiny.Uni
//import io.smallrye.reactive.messaging.MutinyEmitter
//import io.smallrye.reactive.messaging.kafka.KafkaRecord
//import jakarta.enterprise.context.ApplicationScoped
//import jakarta.inject.Inject
//import kvasir.definitions.kg.*
//import kvasir.definitions.kg.changes.ProcessedChange
//import kvasir.definitions.kg.changes.ChangeProcessingHistoryEntry
//import kvasir.definitions.kg.changes.ChangeRequest
//import kvasir.definitions.kg.changes.ChangeStatusCode
//import kvasir.definitions.rdf.KvasirVocab
//import kvasir.plugins.messaging.kafka.Channels
//import kvasir.utils.idgen.ChangeRequestId
//import kvasir.utils.test.commons.TestDataGenerator
//import org.eclipse.microprofile.reactive.messaging.Channel
//import org.junit.jupiter.api.Assertions.assertEquals
//import org.junit.jupiter.api.Test
//import java.time.Duration
//import java.time.temporal.ChronoUnit
//
//@QuarkusTest
//class TestChangeFlow {
//
//    @Channel(Channels.CHANGES_INCOMING_PUBLISH)
//    private lateinit var changeRequestEmitter: MutinyEmitter<ChangeRequest>
//
//    @Inject
//    private lateinit var knowledgeGraph: MockKnowledgeGraph
//
//    @Test
//    fun testBasicFlow() {
//        val podId = "http://example.org/pod"
//        val data = TestDataGenerator.generatePersonData(1)
//        val changeRequestId = ChangeRequestId.generate(podId).encode()
//        // Post a plain change request to the input topic
//        changeRequestEmitter.sendMessage(
//            KafkaRecord.of(
//                podId, ChangeRequest(
//                    id = changeRequestId,
//                    context = mapOf("kss" to KvasirVocab.baseUri),
//                    requestingUser = "test",
//                    podId = podId,
//                    insert = data
//                )
//            )
//        ).await().indefinitely()
//
//        val report = Uni.createFrom().item { knowledgeGraph.changes.find { it.origRequestId == changeRequestId } }
//            .onItem().ifNull().fail()
//            .onFailure()
//            .invoke { _ ->
//                Log.warn("Could not find matching change report yet, retrying...")
//            }
//            .onFailure().retry().withBackOff(Duration.of(50, ChronoUnit.MILLIS)).atMost(10)
//            .await().indefinitely()
//
//        assertEquals(ChangeStatusCode.COMMITTED, report?.processingHistory?.last()?.statusCode)
//    }
//
//}
//
//@ApplicationScoped
//@Mock
//class MockKnowledgeGraph : KnowledgeGraph {
//
//    val changes = mutableListOf<ProcessedChange>()
//
//    override fun processPlain(requests: Collection<ChangeRequest>): Uni<Collection<ProcessedChange>> {
//        val reports = requests.map { request ->
//            ProcessedChange(
//                id = request.changeId!!,
//                origRequestId = request.id,
//                requestingUser = request.requestingUser,
//                podId = request.podId,
//                processingHistory = listOf(ChangeProcessingHistoryEntry(statusCode = ChangeStatusCode.COMMITTED))
//            )
//        }
//        changes.addAll(reports)
//        return Uni.createFrom().item(reports)
//    }
//
//    override fun processStateDependent(request: ChangeRequest): Uni<ProcessedChange> {
//        TODO("Not yet implemented")
//    }
//
//    override fun processReferenced(request: ChangeRequest): Uni<ProcessedChange> {
//        TODO("Not yet implemented")
//    }
//
//    override fun query(request: QueryRequest): Multi<QueryResult> {
//        TODO("Not yet implemented")
//    }
//
//    override fun getChangeRecords(request: ChangeRecordRequest): Uni<PagedResult<ChangeRecord>> {
//        TODO()
//    }
//
//    override fun streamChangeRecords(request: ChangeRecordRequest): Multi<ChangeRecord> {
//        TODO("Not yet implemented")
//    }
//
//    override fun rollback(request: ChangeRollbackRequest): Uni<Void> {
//        TODO("Not yet implemented")
//    }
//
//}