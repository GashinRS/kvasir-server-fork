package kvasir.baseimpl.kg

import graphql.execution.MergedField
import graphql.language.Argument
import graphql.language.ArrayValue
import graphql.language.EnumValue
import graphql.language.StringValue
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironmentImpl
import graphql.schema.GraphQLObjectType
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.converters.multi.MultiRx3Converters
import io.vertx.core.json.Json
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.kafka.client.consumer.KafkaConsumer
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.ChangeRecord
import kvasir.definitions.kg.ChangeRecordRequest
import kvasir.definitions.kg.ChangeRecordType
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.kg.changes.ProcessedChange
import kvasir.definitions.kg.graphql.*
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.persistence.Sort
import kvasir.definitions.rdf.RDFVocab
import kvasir.plugins.messaging.kafka.Channels
import kvasir.plugins.messaging.kafka.KafkaMessagingConfig
import kvasir.utils.graphql.getDirectiveArg
import kvasir.utils.graphql.getFQName
import kvasir.utils.graphql.innerType
import org.eclipse.microprofile.reactive.messaging.Message
import org.reactivestreams.Publisher
import java.util.UUID
import java.util.concurrent.CompletionStage

private const val SUBSCRIPTION_CHANGE_RECORD_PAGE_SIZE = 25_000

@ApplicationScoped
class StreamingDatafetcherFactory(
    private val vertx: Vertx,
    private val kafkaConfig: KafkaMessagingConfig,
    private val knowledgeGraph: DefaultKnowledgeGraph,
    private val repositoryFactory: RepositoryFactory
) {

    fun createDatafetcher(request: QueryRequest): DataFetcher<Publisher<Any>> {
        return DataFetcher<Publisher<Any>> { env ->
            val subscriptionType =
                getFQName(env.fieldDefinition.type.innerType<GraphQLObjectType>(), request.context)
            val triggerType =
                env.fieldDefinition.getDirectiveArg<EnumValue>(DIRECTIVE_TRIGGER_NAME, ARG_TYPE_NAME)?.let {
                    when (it.name) {
                        ENUM_TRIGGER_TYPE_INSERT_VALUE -> ChangeRecordType.INSERT
                        ENUM_TRIGGER_TYPE_DELETE_VALUE -> ChangeRecordType.DELETE
                        else -> null
                    }
                } ?: run {
                    // Fallback to naming convention of the field name
                    val fieldName = env.fieldDefinition.name
                    when {
                        fieldName.endsWith("added", true) || fieldName.endsWith(
                            "inserted",
                            true
                        ) -> ChangeRecordType.INSERT

                        fieldName.endsWith("removed", true) || fieldName.endsWith(
                            "deleted",
                            true
                        ) -> ChangeRecordType.DELETE

                        else -> null
                    }
                }
            val triggerSubjectIn =
                env.fieldDefinition.getDirectiveArg<ArrayValue>(
                    DIRECTIVE_TRIGGER_NAME,
                    ARG_SUBJECT_NAME
                )?.values?.filterIsInstance<StringValue>()
                    ?.map { it.value }?.toSet()
            val triggerPredicateIn =
                env.fieldDefinition.getDirectiveArg<ArrayValue>(
                    DIRECTIVE_TRIGGER_NAME,
                    ARG_PREDICATE_NAME
                )?.values?.filterIsInstance<StringValue>()
                    ?.map { it.value }?.toSet()
                    ?: setOf(RDFVocab.type) // Fallback to triggering on type
            val triggerObjectIn =
                env.fieldDefinition.getDirectiveArg<ArrayValue>(
                    DIRECTIVE_TRIGGER_NAME,
                    ARG_OBJECT_NAME
                )?.values?.filterIsInstance<StringValue>()
                    ?.map { it.value }?.toSet()
                    ?: setOf(subscriptionType) // Fallback to the subscription return type

            streamChangeRecords(request, triggerType, triggerSubjectIn, triggerPredicateIn, triggerObjectIn).onItem()
                .transformToMulti { changes ->
                    if (changes.isNotEmpty()) {
                        val mergedField = env.mergedField
                        val fields = mergedField.fields
                        val newFields = fields.map { field ->
                            field.transform { builder ->
                                builder.arguments(
                                    field.arguments.plus(
                                        Argument.newArgument().name(
                                            ARG_ID_NAME
                                        ).value(
                                            ArrayValue.newArrayValue()
                                                .values(changes.map { StringValue(it.statement.subject) }.distinct())
                                                .build()
                                        ).build()
                                    )
                                )
                            }
                        }
                        val newMergedField = MergedField.newMergedField(newFields).build()
                        val enrichedEnv = DataFetchingEnvironmentImpl.newDataFetchingEnvironment(env)
                            .mergedField(newMergedField)
                            .build()

                        // Fetch request changeId
                        when (triggerType) {
                            ChangeRecordType.INSERT, null -> Uni.createFrom().item(changes.first().changeId)
                            ChangeRecordType.DELETE -> {
                                // For deletions, we need to find the preceding changeId
                                repositoryFactory.getRepository(ProcessedChange::class, request.podId)
                                    .find(
                                        filter = "id < '${changes.first().changeId}'",
                                        sort = Sort.descending(),
                                        limit = 1
                                    )
                                    .map { it.items.firstOrNull()?.id }
                            }
                        }.onItem().transformToMulti { requestChangeId ->
                            val dataFetcher = knowledgeGraph.buildDatafetcher(
                                request,
                                atChangeId = requestChangeId,
                                routeToSubscriptionHandler = false
                            )
                            Uni.createFrom().completionStage { dataFetcher.get(enrichedEnv) as CompletionStage<Any> }
                                .onItem().transformToMulti { result ->
                                    // Create the appropriate result envelope
                                    result?.let { Multi.createFrom().item(it) } ?: Multi.createFrom().empty()
                                }
                        }
                    } else {
                        Multi.createFrom().empty()
                    }
                }
                .concatenate().convert().with(MultiRx3Converters.toFlowable())
        }
    }

    // Individual record streaming not sufficient for filter steps
    // Eventueel streamen tot een bepaalde limit of rekening houden met de filterable concepten.
    private fun streamChangeRecords(
        request: QueryRequest,
        recordType: ChangeRecordType? = null,
        subjectsIn: Set<String>? = null,
        predicatesIn: Set<String>? = null,
        objectsIn: Set<String>? = null,
    ): Multi<List<ChangeRecord>> {
        return streamFrom(Channels.CHANGES_OUTGOING_TOPIC, ProcessedChange::class.java)
            .filter { msg -> msg.payload.podId == request.podId }
            .onItem()
            .transformToUniAndConcatenate { msg ->
                print("Received change report: ${msg.payload}")
                knowledgeGraph.streamChangeRecords(
                    ChangeRecordRequest(
                        podId = msg.payload.podId,
                        changeId = msg.payload.id,
                        pageSize = SUBSCRIPTION_CHANGE_RECORD_PAGE_SIZE,
                        subjectIn = subjectsIn,
                        predicateIn = predicatesIn,
                        objectIn = objectsIn,
                        recordType = recordType
                    )
                ).collect().asList()
            }
    }

    private fun <T> streamFrom(
        targetTopic: String,
        payloadType: Class<T>,
        receiveBacklog: Boolean = true,
    ): Multi<Message<T>> {
        val consumerName = "graphql-subscription-${UUID.randomUUID()}"
        val config = mutableMapOf(
            "bootstrap.servers" to kafkaConfig.bootstrapServers(),
            "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
            "value.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
            "group.id" to consumerName,
            "auto.offset.reset" to if (receiveBacklog) "earliest" else "latest",
            "enable.auto.commit" to "true",
        )
        val consumer = KafkaConsumer.create<String, String>(vertx, config)
        return consumer.subscribe(targetTopic)
            .onItem().transformToMulti {
                consumer.toMulti().map { record ->
                    Message.of(Json.decodeValue(record.value(), payloadType))
                        .withAck { consumer.commit().subscribeAsCompletionStage() }
                }
            }
    }

}
