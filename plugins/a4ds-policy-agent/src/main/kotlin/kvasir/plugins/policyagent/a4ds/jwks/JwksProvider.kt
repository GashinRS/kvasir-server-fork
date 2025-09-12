package kvasir.plugins.policyagent.a4ds.jwks

import io.smallrye.jwt.algorithm.SignatureAlgorithm
import io.smallrye.jwt.util.KeyUtils
import jakarta.inject.Singleton

@Singleton
class JwksProvider {

    private val keypair = KeyUtils.generateKeyPair(256, SignatureAlgorithm.ES256)

    fun getKeyId() = "TODO"
    fun getPrivateKey() = keypair.private
    fun getPublicKey() = keypair.public

}