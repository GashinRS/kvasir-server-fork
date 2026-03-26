package kvasir.utils.idgen

import com.github.f4b6a3.uuid.UuidCreator
import com.github.f4b6a3.uuid.util.UuidUtil
import kvasir.definitions.kg.changes.ChangeRequest
import org.msgpack.core.MessagePack
import java.time.Instant
import java.util.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

class InvalidChangeRequestIdException(msg: String) : RuntimeException(msg)

data class ChangeRequestId(val requestingUser: String, val uuid: UUID) {

    companion object {

        @OptIn(ExperimentalUuidApi::class)
        fun fromId(changeRequestId: String): ChangeRequestId {
            return MessagePack.newDefaultUnpacker(Base64.getUrlDecoder().decode(changeRequestId)).use { unpacker ->
                try {
                    val requestingUser = unpacker.unpackString()
                    val hexUuid = unpacker.unpackString()
                    ChangeRequestId(requestingUser, Uuid.parseHex(hexUuid).toJavaUuid())
                } catch (e: Throwable) {
                    throw InvalidChangeRequestIdException("Invalid change request identifier: $changeRequestId")
                }
            }
        }

        fun generate(requestingUser: String): ChangeRequestId {
            val uuid = UuidCreator.getTimeOrderedEpoch()
            return ChangeRequestId(requestingUser, uuid)
        }

    }

    fun timestamp(): Instant {
        return UuidUtil.getInstant(uuid)
    }

    @OptIn(ExperimentalUuidApi::class)
    fun encode(): String {
        return MessagePack.newDefaultBufferPacker().use { packer ->
            packer.packString(requestingUser)
            packer.packString(uuid.toKotlinUuid().toHexString())
            Base64.getUrlEncoder().encodeToString(packer.toByteArray())
        }
    }

}

fun ChangeRequest.getTimestamp(): Instant {
    return ChangeRequestId.fromId(this.id).timestamp()
}