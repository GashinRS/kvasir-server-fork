//package kvasir.services.changes.slices.filter
//
//import io.quarkus.logging.Log
//import io.quarkus.runtime.Startup
//import io.smallrye.mutiny.Multi
//import io.smallrye.mutiny.Uni
//import io.smallrye.reactive.messaging.MutinyEmitter
//import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
//import jakarta.enterprise.context.ApplicationScoped
//import kvasir.definitions.config.StaticBootstrapConfig
//import kvasir.definitions.kg.ChangeResult
//import kvasir.definitions.kg.ChangeResultSliceFilter
//import kvasir.definitions.kg.SliceEvent
//import kvasir.definitions.kg.SliceEventType
//import kvasir.definitions.kg.SliceStore
//import kvasir.definitions.messaging.Channels
//import org.eclipse.microprofile.reactive.messaging.Channel
//import org.eclipse.microprofile.reactive.messaging.Incoming
//import org.eclipse.microprofile.reactive.messaging.Message
//import kotlin.system.exitProcess
//
//@ApplicationScoped
//class SliceFilter(
//    @Channel(Channels.OUTBOX_SUBSCRIBE)
//    private val outboxResults: Multi<ChangeResult>,
//    @Channel(Channels.SLICE_OUTBOX_PUBLISH)
//    private val sliceOutboxEmitter: MutinyEmitter<ChangeResult>,
//    private val sliceStore: SliceStore,
//    private val podsConfig: StaticBootstrapConfig
//) {
//
//    private lateinit var filters: MutableSet<ChangeResultSliceFilter>
//
//    @Startup
//    fun start() {
//        // Load existing slice filters
//        filters =
//            Multi.createFrom().iterable(podsConfig.pods().map { it.name() })
//                .onItem().transformToMultiAndMerge { podId ->
//                    sliceStore.loadAllFilters(podId).onItem().disjoint<ChangeResultSliceFilter>()
//                }
//                .collect().asList().await().indefinitely().toMutableSet()
//
//        Log.info("Initializing slice filter...")
//        outboxResults
//            .map { event ->
//                val matchingSlices = filters.filter { it.test(event.insert) && it.test(event.delete) }
//                event to matchingSlices
//            }
//            .filter { (_, matchingSlices) -> matchingSlices.isNotEmpty() }
//            .onItem().transformToMultiAndConcatenate { (event, matchingSlices) ->
//                Multi.createFrom().iterable(matchingSlices).onItem().transformToUniAndConcatenate { matchingSlice ->
//                    val metadata =
//                        OutgoingKafkaRecordMetadata.builder<Any?>()
//                            .withTopic(Channels.outboxTopicForSlice(matchingSlice.podId(), matchingSlice.sliceId()))
//                            .build()
//                    sliceOutboxEmitter.sendMessage(Message.of(event).addMetadata(metadata))
//                }
//            }
//            .onFailure().invoke { err ->
//                Log.error("Error in slice event filtering flow, shutting down.", err)
//                exitProcess(1)
//            }
//            .onSubscription().invoke { _ -> Log.debug("Subscribed to slice event filtering flow!") }
//            .subscribe()
//            .with { }
//    }
//
//    /**
//     * Handle slice filter updates
//     */
//    @Incoming(Channels.SLICE_EVENT_SUBSCRIBE)
//    fun onSliceUpdate(sliceEvent: SliceEvent): Uni<Void> {
//        filters.removeIf { it.sliceId() == sliceEvent.sliceId && it.podId() == sliceEvent.podId }
//        return when (sliceEvent.eventType) {
//            SliceEventType.CREATED, SliceEventType.UPDATED -> {
//                sliceStore.loadFilterById(sliceEvent.podId, sliceEvent.sliceId)
//                    .invoke { sliceFilter ->
//                        filters.add(sliceFilter)
//                    }
//                    .replaceWithVoid()
//            }
//
//            SliceEventType.DELETED -> {
//                Uni.createFrom().voidItem()
//            }
//        }
//    }
//
//}