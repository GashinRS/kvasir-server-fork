package kvasir.plugins.policyagent.a4ds.jwks

import io.smallrye.jwt.algorithm.SignatureAlgorithm
import io.vertx.core.json.JsonObject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.jose4j.jwk.JsonWebKey

@Path("/.well-known/jwks.json")
class JwksResource(
    private val jwksProvider: JwksProvider
) {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    fun get(): Map<String, Any> {
        val publicKey = jwksProvider.getPublicKey()
        val jsonKey = JsonWebKey.Factory.newJwk(publicKey).apply {
            algorithm = SignatureAlgorithm.ES256.algorithm
            keyId = jwksProvider.getKeyId()
        }.toJson()
        return mapOf("keys" to listOf(JsonObject(jsonKey).map))
    }

}