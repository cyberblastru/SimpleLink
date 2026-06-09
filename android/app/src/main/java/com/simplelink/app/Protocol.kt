package com.simplelink.app

import org.json.JSONObject
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class MessageType(val id: Byte) {
    AUTH(1),
    AUTH_OK(2),
    AUTH_FAIL(3),
    CLIPBOARD(4),
    FILE_BEGIN(5),
    FILE_CHUNK(6),
    FILE_END(7),
    PING(8),
    PONG(9);

    companion object {
        fun from(id: Byte): MessageType? = entries.find { it.id == id }
    }
}

object LinkProtocol {
    val magic = "SLNK".toByteArray()
    const val DEFAULT_PORT = 9473
    const val CHUNK_SIZE = 64 * 1024

    fun encode(type: MessageType, payload: ByteArray = ByteArray(0)): ByteArray {
        val buffer = ByteBuffer.allocate(9 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(magic)
        buffer.put(type.id)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    fun jsonObject(payload: ByteArray): JSONObject? =
        runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull()

    fun jsonObject(payload: ByteArray, offset: Int, length: Int): JSONObject? =
        runCatching { JSONObject(String(payload, offset, length, Charsets.UTF_8)) }.getOrNull()

    fun jsonBytes(obj: JSONObject): ByteArray = obj.toString().toByteArray(Charsets.UTF_8)
}

class FrameBuffer {
    private var data = ByteArray(256 * 1024)
    private var size = 0

    fun clear() {
        size = 0
    }

    fun append(source: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        ensureCapacity(size + length)
        System.arraycopy(source, offset, data, size, length)
        size += length
    }

    fun decodeFrames(): List<Pair<MessageType, ByteArray>> {
        val messages = mutableListOf<Pair<MessageType, ByteArray>>()
        var offset = 0

        while (size - offset >= 9) {
            if (!hasMagicAt(offset)) {
                offset++
                continue
            }

            val type = MessageType.from(data[offset + 4])
            if (type == null) {
                offset++
                continue
            }

            val length = ByteBuffer.wrap(data, offset + 5, 4)
                .order(ByteOrder.BIG_ENDIAN)
                .int
            if (length < 0) {
                offset++
                continue
            }

            val total = 9 + length
            if (size - offset < total) break

            val payload = data.copyOfRange(offset + 9, offset + total)
            messages.add(type to payload)
            offset += total
        }

        if (offset > 0) {
            val remaining = size - offset
            if (remaining > 0) {
                System.arraycopy(data, offset, data, 0, remaining)
            }
            size = remaining
        }

        return messages
    }

    private fun hasMagicAt(offset: Int): Boolean {
        return data[offset] == LinkProtocol.magic[0] &&
            data[offset + 1] == LinkProtocol.magic[1] &&
            data[offset + 2] == LinkProtocol.magic[2] &&
            data[offset + 3] == LinkProtocol.magic[3]
    }

    private fun ensureCapacity(required: Int) {
        if (required <= data.size) return
        var newSize = data.size
        while (newSize < required) {
            newSize *= 2
        }
        data = data.copyOf(newSize)
    }
}

data class PairingPayload(
    val v: Int,
    val host: String,
    val port: Int,
    val token: String
) {
    companion object {
        fun parse(json: String): PairingPayload? = runCatching {
            val obj = JSONObject(json)
            PairingPayload(
                v = obj.getInt("v"),
                host = obj.getString("host"),
                port = obj.getInt("port"),
                token = obj.getString("token")
            )
        }.getOrNull()
    }
}

enum class DeviceSide(val value: String) {
    MAC("mac"),
    ANDROID("android")
}

fun DataOutputStream.writeFrame(type: MessageType, payload: ByteArray = ByteArray(0)) {
    write(LinkProtocol.encode(type, payload))
}

fun DataOutputStream.writeFrameAndFlush(type: MessageType, payload: ByteArray = ByteArray(0)) {
    writeFrame(type, payload)
    flush()
}
