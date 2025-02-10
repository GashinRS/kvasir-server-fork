package kvasir.utils.idgen

import com.github.f4b6a3.uuid.UuidCreator
import com.github.f4b6a3.uuid.util.UuidUtil
import org.msgpack.core.MessagePack
import java.time.Instant
import java.util.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

class InvalidChangeRequestIdException(msg: String) : RuntimeException(msg)

data class ChangeRequestId(val baseUri: String, val uuid: UUID) {

    companion object {

        @OptIn(ExperimentalUuidApi::class)
        fun fromId(changeRequestId: String): ChangeRequestId {
            val baseUri = changeRequestId.substringBeforeLast("/")
            val idPart = changeRequestId.substringAfterLast("/")
            return MessagePack.newDefaultUnpacker(Base64.getUrlDecoder().decode(idPart)).use { unpacker ->
                try {
                    val hexUuid = unpacker.unpackString()
                    ChangeRequestId(baseUri.removeSuffix("/"), Uuid.parseHex(hexUuid).toJavaUuid())
                } catch (e: Throwable) {
                    throw InvalidChangeRequestIdException("Invalid change request identifier: $changeRequestId")
                }
            }
        }

        fun generate(baseUri: String): ChangeRequestId {
            val uuid = UuidCreator.getTimeOrderedEpoch()
            return ChangeRequestId(baseUri, uuid)
        }

    }

    fun timestamp(): Instant {
        return UuidUtil.getInstant(uuid)
    }

    @OptIn(ExperimentalUuidApi::class)
    fun encode(): String {
        return MessagePack.newDefaultBufferPacker().use { packer ->
            packer.packString(uuid.toKotlinUuid().toHexString())
            "$baseUri/${Base64.getUrlEncoder().encodeToString(packer.toByteArray())}"
        }
    }

}