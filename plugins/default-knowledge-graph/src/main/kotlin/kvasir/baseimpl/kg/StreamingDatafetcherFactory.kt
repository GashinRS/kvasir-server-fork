package kvasir.baseimpl.kg

import graphql.execution.MergedField
import graphql.language.Argument
import graphql.language.ArrayValue
import graphql.language.StringValue
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironmentImpl
import graphql.schema.GraphQLObjectType
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.converters.multi.MultiRx3Converters
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.ChangeRecord
import kvasir.definitions.kg.ChangeRecordRequest
import kvasir.definitions.kg.ChangeRecordType
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.kg.changes.ChangeReport
import kvasir.definitions.kg.graphql.*
import kvasir.definitions.rdf.RDFVocab
import kvasir.plugins.messaging.kafka.Channels
import kvasir.utils.graphql.getDirectiveArg
import kvasir.utils.graphql.getFQName
import kvasir.utils.graphql.innerType
import kvasir.utils.idgen.ChangeRequestId
import org.eclipse.microprofile.reactive.messaging.Channel
import org.reactivestreams.Publisher
import java.util.concurrent.CompletionStage

@ApplicationScoped
class StreamingDatafetcherFactory(
    @Channel(Channels.OUTBOX_SUBSCRIBE)
    private val outbox: Multi<ChangeReport>,
    private val knowledgeGraph: DefaultKnowledgeGraph
) {

    fun createDatafetcher(request: QueryRequest): DataFetcher<Publisher<Any>> {
        return DataFetcher<Publisher<Any>> { env ->
            val subscriptionType =
                getFQName(env.fieldDefinition.type.innerType<GraphQLObjectType>(), request.context)
            val triggerType =
                env.fieldDefinition.getDirectiveArg<StringValue>(DIRECTIVE_TRIGGER_NAME, ARG_TYPE_NAME)?.let {
                    when (it.value) {
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
                                                .values(changes.map { StringValue(it.statement.subject) }.distinct()).build()
                                        ).build()
                                    )
                                )
                            }
                        }
                        val newMergedField = MergedField.newMergedField(newFields).build()
                        val enrichedEnv = DataFetchingEnvironmentImpl.newDataFetchingEnvironment(env)
                            .mergedField(newMergedField)
                            .build()
                        val changeRequestId = ChangeRequestId.fromId(changes.first().changeRequestId)
                        val requestTimestamp = when (triggerType) {
                            ChangeRecordType.INSERT, null -> changeRequestId.timestamp()
                            ChangeRecordType.DELETE -> changeRequestId.timestamp()
                                .minusNanos(1) // State before the statements were deleted
                        }
                        println("Query timestamp: $requestTimestamp (triggerType: $triggerType)")
                        val dataFetcher = knowledgeGraph.buildDatafetcher(
                            request,
                            atTimestamp = requestTimestamp,
                            routeToSubscriptionHandler = false
                        )
                        Uni.createFrom().completionStage { dataFetcher.get(enrichedEnv) as CompletionStage<Any> }
                            .map { result ->
                                // Create the appropriate result envelope
                                result as Any
                            }
                            .toMulti()
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
        return outbox.filter { msg -> msg.podId == request.podId }
            .onItem()
            .transformToUniAndConcatenate { msg ->
                print("Received change report: $msg")
                knowledgeGraph.getChangeRecords(
                    ChangeRecordRequest(
                        podId = msg.podId,
                        changeRequestId = msg.id,
                        subjectIn = subjectsIn,
                        predicateIn = predicatesIn,
                        objectIn = objectsIn,
                        recordType = recordType
                    )
                ).map {
                    it.items
                }
            }
    }

}