package kvasir.utils.cursors

import org.msgpack.core.MessagePack
import java.util.*

class OffsetBasedCursor(val offset: Long) {

    companion object {

        fun fromString(cursor: String): OffsetBasedCursor? {
            return MessagePack.newDefaultUnpacker(Base64.getUrlDecoder().decode(cursor)).use { unpacker ->
                try {
                    unpacker.unpackMapHeader()
                    unpacker.unpackString()
                    unpacker.unpackLong()
                } catch (e: Throwable) {
                    null
                }
            }?.let { OffsetBasedCursor(it) }
        }

    }

    fun encode(): String {
        return MessagePack.newDefaultBufferPacker().use { packer ->
            packer.packMapHeader(1)
            packer.packString("o")
            packer.packLong(offset)
            Base64.getUrlEncoder().encodeToString(packer.toByteArray())
        }
    }
}