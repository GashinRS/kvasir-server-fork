package kvasir.plugins.policyagent.openfga

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonProperty
import idlab.quarkus.ext.pep.openfga.model.annotations.OpenFgaPolicyEnforcer
import idlab.quarkus.ext.pep.openfga.runtime.OpenFgaManager
import io.quarkiverse.openfga.client.model.RelObject
import io.quarkiverse.openfga.client.model.RelTuple
import io.quarkiverse.openfga.client.model.RelTupleDefinition
import io.quarkiverse.openfga.client.model.RelUser
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Response
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.rdf.*
import kvasir.plugins.policyagent.openfga.utils.getContextForParents
import kvasir.utils.http.KvasirUriInfo
import kvasir.utils.rdf.RDFTransformer
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter
import org.eclipse.microprofile.openapi.annotations.responses.APIResponseSchema
import org.jboss.resteasy.reactive.RestResponse
import paging.Paging
import java.util.*
import kotlin.jvm.optionals.getOrNull

@Path("{podId}/rebac")
@Consumes(RDFMediaTypes.JSON_LD)
@Produces(RDFMediaTypes.JSON_LD)
class FgaResource(
    private val fgaManager: OpenFgaManager,
    private val uriInfo: KvasirUriInfo
) {

    @Path("relationships")
    @GET
    @APIResponseSchema(RelationshipGraph::class)
    @OpenFgaPolicyEnforcer
    fun read(
        @PathParam("podId") podId: String,
        @QueryParam("pageSize") @Parameter(required = false) @DefaultValue("100") pageSize: Int,
        @QueryParam("cursor") @Parameter(required = false) cursor: Optional<String>
    ): Uni<RestResponse<JSONObject>> {
        return fgaManager.readTuples(podId, Paging.size(pageSize).andCursor(cursor.getOrNull())).map { tuples ->
            RestResponse.ResponseBuilder.ok(convertFromTuples(tuples.items)).apply {
                if (tuples.hasNextPage()) {
                    this.link(uriInfo.getAbsoluteUri("cursor" to tuples.cursor!!), "next")
                }
            }.build()
        }
    }

    @Path("relationships")
    @POST
    @OpenFgaPolicyEnforcer
    fun write(@PathParam("podId") podId: String, transaction: WriteTransaction): Uni<Response> {
        return run {
            // Process deletes
            if (transaction.delete.isNotEmpty()) {
                val tuples = convertToTuples(transaction.delete)
                fgaManager.removeTuples(podId, tuples)
            } else {
                Uni.createFrom().voidItem()
            }
        }.chain { _ ->
            // Process inserts
            if (transaction.insert.isNotEmpty()) {
                val tuples = convertToTuples(transaction.insert)
                fgaManager.addTuples(podId, tuples)
            } else {
                Uni.createFrom().voidItem()
            }
        }.map { Response.noContent().build() }
            .onFailure().invoke { err -> err.printStackTrace() }
    }

    @Path("check")
    @POST
    @OpenFgaPolicyEnforcer
    fun check(@PathParam("podId") podId: String, input: JSONObject): Uni<CheckResult> {
        val relTuples = convertToTuples(listOf(input))
        return Uni.createFrom().voidItem().chain { _ ->
            when (relTuples.size) {
                0 -> Uni.createFrom().failure(IllegalArgumentException("No relationship provided in input."))
                1 -> {
                    val checkRel = relTuples.first()
                    fgaManager.check(podId, checkRel, getContextForParents(checkRel.`object`.id))
                }

                else -> Uni.createFrom()
                    .failure(IllegalArgumentException("Multiple relationships provided in input. Only one is allowed (except for @type)."))
            }
        }.map { allowed ->
            CheckResult(allowed)
        }
    }

    private fun convertToTuples(jsonLdList: List<JSONObject>): List<RelTupleDefinition> {
        val statements = RDFTransformer.toStatements(jsonLdList)
        val instanceToType = statements.filter { it.predicate == RDFVocab.type }
            .associate { it.subject to it.`object` as String }
        return statements.filterNot { it.predicate == RDFVocab.type }.map { statement ->
            val userType = instanceToType[statement.subject]
                ?: throw IllegalArgumentException("No type found for entity '${statement.subject}'")
            val objectType = instanceToType[statement.`object`]
                ?: throw IllegalArgumentException("No type found for entity '${statement.`object`}'")
            RelTupleDefinition.builder()
                .user(RelUser.of(parseType(userType), encodeId(userType, statement.subject)))
                .relation(parseRelation(objectType, statement.predicate))
                .`object`(RelObject.of(parseType(objectType), encodeId(objectType, statement.`object`.toString())))
                .build()
        }
    }

    private fun convertFromTuples(tuples: Collection<RelTuple>): JSONObject {
        val jsonld = mapOf(JsonLdKeywords.graph to tuples.map { tuple ->
            val userId = decodeId(tuple.key.user.type, tuple.key.user.id)
            val objectId = decodeId(tuple.key.`object`.type, tuple.key.`object`.id)
            mapOf(
                JsonLdKeywords.id to userId,
                JsonLdKeywords.type to FgaVocab.baseUri + tuple.key.user.type.replaceFirstChar(Char::titlecase),
                FgaVocab.baseUri + tuple.key.relation to mapOf(
                    JsonLdKeywords.id to objectId,
                    JsonLdKeywords.type to FgaVocab.baseUri + tuple.key.`object`.type.replaceFirstChar(Char::titlecase)
                )
            )
        })
        return jsonld
    }

    private fun parseType(fqnType: String): String {
        if (!FgaVocab.ALL_TYPES.contains(fqnType)) {
            throw IllegalArgumentException(
                "Unknown type: $fqnType. Supported types are: ${
                    FgaVocab.ALL_TYPES.joinToString(
                        ", "
                    )
                }"
            )
        }
        return fqnType.removePrefix(FgaVocab.baseUri).replaceFirstChar(Char::lowercase)
    }

    private fun encodeId(fqType: String, id: String): String {
        return when (fqType) {
            // Encoding is done by the openfga-pep extenstion
            FgaVocab.Group, FgaVocab.User -> id

            FgaVocab.Resource -> {
                // Check if the ID is prefixed with the base URI of the Pod
                if (!id.startsWith(uriInfo.getBaseUri())) {
                    throw IllegalArgumentException(
                        "Resource ID '$id' must be prefixed with the Pod base URI '${uriInfo.getBaseUri()}'."
                    )
                }
                // Remove the base URI to get the relative ID
                id.removePrefix(uriInfo.getBaseUri())
            }

            else -> throw IllegalArgumentException("Unknown type: $fqType. Supported types are: ${FgaVocab.ALL_TYPES}")
        }
    }

    private fun decodeId(type: String, id: String): String {
        val fqType = FgaVocab.baseUri + type.replaceFirstChar(Char::titlecase)
        return when (fqType) {
            // Decoding is done by the openfga-pep extenstion
            FgaVocab.Group, FgaVocab.User -> id
            FgaVocab.Resource -> uriInfo.getBaseUri() + id
            else -> throw IllegalArgumentException("Unknown type: $fqType. Supported types are: ${FgaVocab.ALL_TYPES}")
        }
    }

    private fun parseRelation(objectType: String, fqnPredicate: String): String {
        return when (objectType) {
            FgaVocab.Group -> {
                if (!FgaVocab.GROUP_RELATIONS.contains(fqnPredicate)) {
                    throw IllegalArgumentException(
                        "Unknown relation '$fqnPredicate' to type '$objectType'. Supported relations are: ${
                            FgaVocab.GROUP_RELATIONS.joinToString(
                                ", "
                            )
                        }"
                    )
                }
                fqnPredicate.removePrefix(FgaVocab.baseUri)
            }

            FgaVocab.Resource -> {
                if (!FgaVocab.RESOURCE_RELATIONS.contains(fqnPredicate)) {
                    throw IllegalArgumentException(
                        "Unknown relation '$fqnPredicate' to type '$objectType'. Supported relations are: ${
                            FgaVocab.RESOURCE_RELATIONS.joinToString(
                                ", "
                            )
                        }"
                    )
                }
                fqnPredicate.removePrefix(FgaVocab.baseUri)
            }

            FgaVocab.User -> throw IllegalArgumentException("No relations exists that can target '$objectType'.")
            else -> throw IllegalArgumentException(
                "Unknown type: $objectType. Supported types are: ${
                    FgaVocab.ALL_TYPES.joinToString(
                        ", "
                    )
                }"
            )
        }
    }

}

@GenerateNoArgConstructor
data class WriteTransaction(
    @get:JsonProperty(KvasirVocab.insert)
    @JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    val insert: List<JSONObject> = emptyList(),
    @get:JsonProperty(KvasirVocab.delete)
    @JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    val delete: List<JSONObject> = emptyList()
)

@GenerateNoArgConstructor
data class RelationshipGraph(
    @get:JsonProperty(JsonLdKeywords.context)
    val context: JSONObject = mapOf("kss" to KvasirVocab.baseUri, "kss-fga" to FgaVocab.baseUri),
    @get:JsonProperty(JsonLdKeywords.graph)
    val graph: List<JSONObject>
)

@GenerateNoArgConstructor
data class CheckResult(
    @get:JsonProperty(FgaVocab.allowed)
    val allowed: Boolean
)