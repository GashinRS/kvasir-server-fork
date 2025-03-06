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
import kvasir.definitions.messaging.Channels
import kvasir.definitions.rdf.RDFVocab
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
            streamChangeRecords(request).onItem().transformToMulti { change ->
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
                    env.fieldDefinition.getDirectiveArg<ArrayValue>(DIRECTIVE_TRIGGER_NAME, ARG_SUBJECT_NAME)
                        ?.let { subjArr ->
                            subjArr.values.filterIsInstance<StringValue>().map { it.value }
                        }
                val triggerPredicateIn =
                    env.fieldDefinition.getDirectiveArg<ArrayValue>(DIRECTIVE_TRIGGER_NAME, ARG_PREDICATE_NAME)
                        ?.let { predicateArr ->
                            predicateArr.values.filterIsInstance<StringValue>().map { it.value }
                        } ?: setOf(RDFVocab.type) // Fallback to triggering on type
                val triggerObjectIn =
                    env.fieldDefinition.getDirectiveArg<ArrayValue>(DIRECTIVE_TRIGGER_NAME, ARG_OBJECT_NAME)
                        ?.let { objArr ->
                            objArr.values.filterIsInstance<StringValue>().map { it.value }
                        } ?: setOf(subscriptionType) // Fallback to the subscription return type
                if (change.type == triggerType && (triggerSubjectIn == null || triggerSubjectIn.contains(change.statement.subject)) && triggerPredicateIn.contains(
                        change.statement.predicate
                    ) && triggerObjectIn.contains(change.statement.`object`)
                ) {
                    val mergedField = env.mergedField
                    val fields = mergedField.fields
                    val newFields = fields.map { field ->
                        field.transform { builder ->
                            builder.arguments(
                                field.arguments.plus(
                                    Argument.newArgument().name(
                                        ARG_ID_NAME
                                    ).value(StringValue.of(change.statement.subject)).build()
                                )
                            )
                        }
                    }
                    val newMergedField = MergedField.newMergedField(newFields).build()
                    val enrichedEnv = DataFetchingEnvironmentImpl.newDataFetchingEnvironment(env)
                        .mergedField(newMergedField)
                        .build()
                    val changeRequestId = ChangeRequestId.fromId(change.changeRequestId)
                    val requestTimestamp = when (triggerType) {
                        ChangeRecordType.INSERT -> changeRequestId.timestamp()
                        ChangeRecordType.DELETE -> changeRequestId.timestamp()
                            .minusNanos(1) // State before the statements were deleted
                    }
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
            }.concatenate().convert().with(MultiRx3Converters.toFlowable())
        }
    }

    private fun streamChangeRecords(request: QueryRequest): Multi<ChangeRecord> {
        return outbox.filter { msg -> msg.podId == request.podId }
            .onItem()
            .transformToMultiAndConcatenate { msg ->
                knowledgeGraph.streamChangeRecords(
                    ChangeRecordRequest(
                        podId = msg.podId,
                        changeRequestId = msg.id
                    )
                )
            }
    }

}