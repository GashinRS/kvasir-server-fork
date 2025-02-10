package kvasir.plugins.kg.clickhouse.backends

import graphql.language.*
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import graphql.util.TreeTransformerUtil
import io.smallrye.mutiny.Uni
import io.vertx.core.json.JsonObject
import jakarta.inject.Singleton
import kvasir.definitions.kg.ChangeRecord
import kvasir.definitions.kg.ChangeRecordType
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.kg.RDFStatement
import kvasir.definitions.kg.changes.ChangeRequestTxBuffer
import kvasir.definitions.kg.graphql.ARG_CLASS_NAME
import kvasir.definitions.kg.graphql.DIRECTIVE_STORAGE_NAME
import kvasir.definitions.kg.timeseries.Observation
import kvasir.definitions.rdf.SAREFVocab
import kvasir.definitions.rdf.XSDVocab
import kvasir.definitions.reactive.skipToLast
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.graphql.SQLConvertor
import kvasir.plugins.kg.clickhouse.graphql.SQLConvertorMode
import kvasir.plugins.kg.clickhouse.graphql.saref.SarefDatafetcher
import kvasir.plugins.kg.clickhouse.graphql.saref.TSQLConvertor
import kvasir.plugins.kg.clickhouse.specs.*
import kvasir.plugins.kg.clickhouse.utils.databaseFromPodId
import kvasir.utils.graphql.KvasirNodeVisitor
import kvasir.utils.graphql.getFQName
import kvasir.utils.rdf.RDFTransformer
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.jvm.optionals.getOrNull

@Singleton
class SarefTimeseriesStorageBackend(
    @ConfigProperty(name = "kvasir.plugins.kg.clickhouse.storage-buffer", defaultValue = "100000")
    private val bufferSize: Int,
    private val sarefDatafetcher: SarefDatafetcher,
    clickhouseClient: ClickhouseClient,
) : AbstractStorageBackend(TIME_SERIES_DATA_TABLE, TIME_SERIES_DATA_COLUMNS, clickhouseClient) {

    override fun process(buffer: ChangeRequestTxBuffer): Uni<Void> {
        // Look for subjects that have a SAREF timestamp property (there is a high change that these represent an observation).
        return buffer.stream(filterByPredicate = SAREFVocab.hasTimestamp)
            .map { it.statement.subject }
            .select().distinct()
            .onItem().transformToUniAndMerge { matchingSubject ->
                // For each matching subject, map its statements into a timeseries entry
                buffer.stream(filterBySubject = matchingSubject).collect().asSet().map { eventRecords ->
                    eventRecords.find { it.statement.predicate == SAREFVocab.hasValue }?.let { valueRecord ->
                        // At this time an observation can only exist in a single graph, we pick the value for the hasValue statement here.
                        val graph = valueRecord.statement.graph
                        val timestamp =
                            mapTimestamp(eventRecords.find { it.statement.predicate == SAREFVocab.hasTimestamp }!!.statement)
                        Observation(
                            id = matchingSubject,
                            changeRequestId = valueRecord.changeRequestId,
                            changeRequestTimestamp = valueRecord.timestamp,
                            timestamp = timestamp,
                            // Do not include the attributes that are variable per observation as labels
                            labels = eventRecords.filterNot {
                                it.statement.predicate in setOf(
                                    SAREFVocab.hasTimestamp,
                                    SAREFVocab.hasValue
                                )
                            }.associate {
                                val processedStat = RDFTransformer.asRDF4JStatement(it.statement)
                                val processedStatValue = when {
                                    processedStat.`object`.isIRI -> processedStat.`object`.stringValue()
                                    else -> processedStat.`object`.toString()
                                }
                                it.statement.predicate to processedStatValue
                            },
                            value = valueRecord.statement.`object`,
                            dataType = valueRecord.statement.dataType,
                            language = valueRecord.statement.language,
                            graph = graph
                        )
                    } to eventRecords
                }
            }
            // Filter out matches that did not result in an Observation
            .filter { it.first != null }
            .group().intoLists().of(bufferSize)
            .onItem().transformToUniAndConcatenate { batch ->
                // Store the observations in the Timeseries store
                write(databaseFromPodId(buffer.request.podId), batch.mapNotNull { it.first }).chain { _ ->
                    // Remove the matching records from the tx buffer
                    buffer.remove(batch.flatMap { it.second }, true)
                }
            }
            .skipToLast()
    }

    override fun datafetcher(podId: String, context: Map<String, Any>, atTimestamp: Instant?): DataFetcher<Any>? {
        return sarefDatafetcher.getDatafetcher(podId, context, atTimestamp)
    }

    override fun count(
        podId: String,
        context: Map<String, Any>,
        atTimestamp: Instant?,
        env: DataFetchingEnvironment
    ): Uni<Long> {
        val databaseName = databaseFromPodId(podId)
        val sqlConvertor = TSQLConvertor(
            context,
            atTimestamp,
            databaseName,
            TIME_SERIES_DATA_TABLE,
            env.field,
            env.fieldDefinition,
            env,
            SQLConvertorMode.COUNT
        )
        val (sql, _) = sqlConvertor.toSQL()
        return clickhouseClient.query(GenericQuerySpec(databaseName, TIME_SERIES_DATA_TABLE, listOf("totalCount")), sql)
            .map { result ->
                result[0]["totalCount"].toString().toLong()
            }
    }

    private fun mapTimestamp(timestampStatement: RDFStatement): Instant {
        // TODO: more robust processing, e.g. take into account and support different datatypes
        val dataTimeStr = timestampStatement.`object`.toString()
        return try {
            Instant.parse(dataTimeStr)
        } catch (err: DateTimeParseException) {
            // The datatime string is maybe missing a 'Z'?
            Instant.parse("${dataTimeStr}Z")
        }
    }

    private fun write(database: String, observations: List<Observation>): Uni<Void> {
        return clickhouseClient.insert(ObservationInsertRecordSpec(database), observations)
    }

    override fun resultToChangeRecord(result: Map<String, Any>): List<ChangeRecord> {
        val subject = result["id"] as String
        val graph = result["graph"] as String
        val changeRequestId = result["change_request_id"] as String
        val changeRequestTimestamp = Instant.parse(result["change_request_ts"] as String)
        val dataType = result["value_datatype"] as String
        return listOf(
            // The SAREF hasTimestamp property
            ChangeRecord(
                statement = RDFStatement(
                    subject = subject,
                    predicate = SAREFVocab.hasTimestamp,
                    `object` = result["timestamp"] as String,
                    graph = graph,
                    dataType = XSDVocab.dateTime,
                ),
                changeRequestId = changeRequestId,
                timestamp = changeRequestTimestamp,
                type = ChangeRecordType.INSERT,
            ),
            // The SAREF hasValue property
            ChangeRecord(
                statement = RDFStatement(
                    subject = subject,
                    predicate = SAREFVocab.hasValue,
                    `object` = when (dataType) {
                        XSDVocab.int, XSDVocab.long, XSDVocab.decimal, XSDVocab.double, XSDVocab.float, XSDVocab.integer -> result["value_number"]
                        XSDVocab.boolean -> result["value_bool"]
                        else -> result["value_string"]
                    }!!,
                    graph = graph,
                    dataType = dataType,
                    language = (result["value_lang"] as String).takeIf { it.isNotBlank() }
                ),
                changeRequestId = changeRequestId,
                timestamp = changeRequestTimestamp,
                type = ChangeRecordType.INSERT,
            )
        ) + (result["labels"] as JsonObject).map { (labelName, labelValue) ->
            // The remaining records are the labels that were attached to the observation
            val rdfLiteral = SimpleValueFactory.getInstance().createLiteral(labelValue as String)
            ChangeRecord(
                statement = RDFStatement(
                    subject = subject,
                    predicate = labelName,
                    `object` = rdfLiteral.stringValue(),
                    graph = graph,
                    dataType = rdfLiteral.datatype.stringValue(),
                    language = rdfLiteral.language.getOrNull()
                ),
                changeRequestId = changeRequestId,
                timestamp = changeRequestTimestamp,
                type = ChangeRecordType.INSERT,
            )
        }
    }

    override fun prepareQuery(request: QueryRequest, queryDocument: Document): Document {
        return AstTransformer().transform(queryDocument, MatchingPathVisitor(request.context)) as Document
    }

}

class MatchingPathVisitor(rdfContext: Map<String, Any>) : KvasirNodeVisitor(rdfContext) {

    override fun visitField(node: Field, context: TraverserContext<Node<*>>): TraversalControl {
        // If the selectionSet for the Field includes SAREF timeseries fields...
        if (node.selectionSet != null && node.selectionSet.selections.filterIsInstance<Field>()
                .mapNotNull { selection ->
                    try {
                        getFQName(selection, providedContext)
                    } catch (err: IllegalArgumentException) {
                        null
                    }
                }.any { it == SAREFVocab.hasValue || it == SAREFVocab.hasTimestamp }
        ) {
            // ... add a storage directive, so the KG resolver knows which backend to use for data fetching
            val transformedNode = node.transform {
                it.directive(
                    Directive.newDirective().name(DIRECTIVE_STORAGE_NAME)
                        .argument(
                            Argument.newArgument().name(ARG_CLASS_NAME)
                                .value(StringValue.of(SarefTimeseriesStorageBackend::class.qualifiedName)).build()
                        ).build()
                )
            }
            return TreeTransformerUtil.changeNode(context, transformedNode)
        } else {
            return TraversalControl.CONTINUE
        }
    }

}