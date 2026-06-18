package kvasir.services.api.kg.changes

import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.KafkaRecord
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.core.Link
import jakarta.ws.rs.core.Response
import kvasir.definitions.kg.*
import kvasir.definitions.kg.changes.*
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.reactive.notNullOrFail
import kvasir.definitions.reactive.toUni
import kvasir.plugins.messaging.kafka.Channels
import kvasir.utils.http.KvasirUriInfo
import kvasir.utils.http.getChildUri
import kvasir.utils.http.getParentUri
import kvasir.utils.idgen.ChangeRequestId
import kvasir.utils.idgen.InvalidChangeRequestIdException
import kvasir.utils.rdf.RDFTransformer
import org.apache.kafka.common.errors.RecordTooLargeException
import org.eclipse.microprofile.reactive.messaging.Channel
import org.jboss.resteasy.reactive.RestResponse
import java.net.URI
import java.util.*
import java.util.concurrent.TimeoutException

abstract class AbstractChangesApi {

    @Inject
    protected lateinit var repositoryFactory: RepositoryFactory

    @Inject
    protected lateinit var knowledgeGraph: KnowledgeGraph

    @Inject
    protected lateinit var syncChangeAwaiter: SyncChangeAwaiter

    @Channel(Channels.CHANGES_INCOMING_PUBLISH)
    protected lateinit var changeEmitter: MutinyEmitter<ChangeRequest>

    @Inject
    protected lateinit var uriInfo: KvasirUriInfo

    @Inject
    protected lateinit var securityIdentity: Instance<SecurityIdentity>

    protected fun handlePublishChange(fqPodId: String, changeCommand: ChangeRequest, sync: Boolean): Uni<Response> {
        return if (sync) {
            // Subscribe eagerly BEFORE emitting to Kafka to avoid missing the result.
            val syncFuture = syncChangeAwaiter.awaitChange(changeCommand.id)
                .subscribeAsCompletionStage()

            changeEmitter.sendMessage(KafkaRecord.of(fqPodId, changeCommand))
                .chain { _ -> syncFuture.toUni() }
                .map { processedChange ->
                    Response.status(mapProcessedChangeStatusCode(processedChange))
                        .entity(JsonLdHelper.encode(qualifyProcessedChangeId(uriInfo, processedChange))).build()
                }
                .onFailure(TimeoutException::class.java)
                .recoverWithItem { _ -> Response.status(Response.Status.REQUEST_TIMEOUT).build() }
                .onFailure(RecordTooLargeException::class.java)
                .recoverWithItem { _ -> Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE).build() }
        } else {
            changeEmitter.sendMessage(KafkaRecord.of(fqPodId, changeCommand))
                .map { _ ->
                    Response.created(URI.create("${uriInfo.getResourceUri()}/pending/${changeCommand.id}"))
                        .build()
                }
                .onFailure(RecordTooLargeException::class.java)
                .recoverWithItem { _ -> Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE).build() }
        }
    }

    protected fun fetchChange(
        fqPodId: String,
        changeId: String,
        sliceId: String? = null,
        sliceTag: String? = null
    ): Uni<ProcessedChange> {
        val isUUIDStateId = try {
            UUID.fromString(changeId)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
        val changeRequestId = try {
            ChangeRequestId.fromId(changeId)
        } catch (_: InvalidChangeRequestIdException) {
            null
        }

        return when {
            isUUIDStateId -> repositoryFactory.getRepository(ProcessedChange::class, fqPodId).findById(changeId)
                .notNullOrFail { NotFoundException() }

            changeRequestId != null ->
                // Try to find a matching record based on the change request ID (for backwards compatibility)
                repositoryFactory.getRepository(ProcessedChange::class, fqPodId)
                    .find("origRequestId=='$changeId'", limit = 1)
                    .map { results ->
                        // Return result if found, otherwise create a temporary QUEUED state
                        results.items.firstOrNull() ?: ProcessedChange(
                            id = changeRequestId.uuid.toString(), // Temp state id
                            origRequestId = changeId,
                            createdBy = changeRequestId.requestingUser,
                            podId = fqPodId,
                            sliceId = sliceId,
                            sliceTag = sliceTag,
                            processingHistory = listOf(
                                ChangeProcessingHistoryEntry(
                                    changeRequestId.timestamp(),
                                    ChangeStatusCode.QUEUED
                                )
                            )
                        )
                    }

            else -> Uni.createFrom().failure(NotFoundException("No change report found!"))
        }
    }

    protected fun handlePendingChangeRequest(
        fqPodId: String,
        fqRequestId: String,
        requestId: String,
        fqSliceId: String? = null,
        sliceTag: String? = null,
    ): Uni<Response> {
        val filter = listOfNotNull(
            "origRequestId=='$requestId'",
            fqSliceId?.let { "sliceId=='$fqSliceId'" },
            sliceTag?.let { "sliceTag=='$sliceTag'" },
        ).joinToString(" and ")
        return repositoryFactory.getRepository(ProcessedChange::class, fqPodId)
            .find(filter, limit = 1)
            .map { results ->
                if (results.items.isEmpty()) {
                    val parsedRequestId = ChangeRequestId.fromId(requestId)
                    // Sending an entity via Response does not pass by JsonLDBodyInterceptor, so convert manually to JSON-LD
                    Response.status(Response.Status.OK)
                        .entity(
                            JsonLdHelper.encode(
                                PendingChangeRequest(
                                    id = requestId,
                                    timestamp = parsedRequestId.timestamp(),
                                    requestingUser = parsedRequestId.requestingUser
                                ),
                                mapOf("kss" to KvasirVocab.baseUri)
                            )
                        )
                        .build()
                } else {
                    val changeId = results.items[0].id
                    val fqChangeId = fqSliceId?.let {
                        sliceTag?.let { tagId -> "$it/tags/$tagId/changes/$changeId" } ?: "$it/changes/$changeId"
                    } ?: "$fqPodId/changes/$changeId"
                    // Change was processed, permanently redirect to changes API
                    Response.seeOther(URI.create(fqChangeId)).build()
                }
            }
    }

    protected fun listChangeRecords(
        uriInfo: KvasirUriInfo,
        request: ChangeRecordRequest
    ): Uni<RestResponse<ChangeRecords>> {
        return knowledgeGraph.getChangeRecords(request).map { results ->
            try {
                val response = if (results.items.isNotEmpty()) {
                    ChangeRecords(
                        mapOf("kss" to KvasirVocab.baseUri),
                        request.changeId,
                        results.items.first().timestamp,
                        results.items.filter { it.type == ChangeRecordType.DELETE }
                            .map { it.statement }.takeIf { it.isNotEmpty() }
                            ?.let { RDFTransformer.statementsToJsonLD(it) },
                        results.items.filter { it.type == ChangeRecordType.INSERT }
                            .map { it.statement }.takeIf { it.isNotEmpty() }
                            ?.let { RDFTransformer.statementsToJsonLD(it) }
                    )
                } else {
                    val parsedId = ChangeRequestId.Companion.fromId(request.changeId)
                    ChangeRecords(
                        mapOf("kss" to KvasirVocab.baseUri),
                        request.changeId,
                        parsedId.timestamp()
                    )
                }
                RestResponse.ResponseBuilder.ok(response)
                    .links(*generateLinks(uriInfo, results))
                    .build()
            } catch (err: InvalidChangeRequestIdException) {
                RestResponse.status(Response.Status.BAD_REQUEST)
            }
        }
    }

    protected fun generateLinks(uriInfo: KvasirUriInfo, result: PagedResult<*>): Array<Link> {
        return listOfNotNull(
            result.nextCursor?.let {
                Link.fromUri(uriInfo.getAbsoluteUri("cursor" to it)).rel("next").build()
            },
            result.previousCursor?.let {
                Link.fromUri(uriInfo.getAbsoluteUri("cursor" to it)).rel("previous").build()
            }
        ).toTypedArray()
    }

    protected fun qualifyProcessedChangeId(uriInfo: KvasirUriInfo, processedChange: ProcessedChange): ProcessedChange {
        val resourceUri = uriInfo.getResourceUri()
        val parentUri = if (resourceUri.path.endsWith(processedChange.id)) resourceUri.getParentUri() else resourceUri
        return processedChange.copy(
            id = parentUri.getChildUri(processedChange.id).toASCIIString(),
            origRequestId = "${parentUri}/pending/${processedChange.origRequestId}"
        )
    }

    protected fun mapProcessedChangeStatusCode(processedChange: ProcessedChange): Response.Status {
        return when (processedChange.getStatusCode()) {
            ChangeStatusCode.COMMITTED -> Response.Status.OK
            ChangeStatusCode.ASSERTION_FAILED, ChangeStatusCode.VALIDATION_ERROR -> Response.Status.BAD_REQUEST
            else -> Response.Status.INTERNAL_SERVER_ERROR
        }
    }

}