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

    fun decodeFrames(buffer: MutableList<Byte>): List<Pair<MessageType, ByteArray>> {
        val messages = mutableListOf<Pair<MessageType, ByteArray>>()

        while (buffer.size >= 9) {
            val prefix = ByteArray(4)
            for (i in 0 until 4) prefix[i] = buffer[i]
            if (!prefix.contentEquals(magic)) {
                buffer.removeAt(0)
                continue
            }

            val typeRaw = buffer[4]
            val type = MessageType.from(typeRaw)
            if (type == null) {
                buffer.removeAt(0)
                continue
            }
            val lengthBytes = ByteArray(4)
            for (i in 0 until 4) lengthBytes[i] = buffer[5 + i]
            val length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.BIG_ENDIAN).int
            val total = 9 + length
            if (buffer.size < total) break

            repeat(9) { buffer.removeAt(0) }
            val payload = ByteArray(length)
            for (i in 0 until length) payload[i] = buffer.removeAt(0)
            messages.add(type to payload)
        }

        return messages
    }

    fun jsonObject(payload: ByteArray): JSONObject? =
        runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull()

    fun jsonBytes(obj: JSONObject): ByteArray = obj.toString().toByteArray(Charsets.UTF_8)
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
    flush()
}
