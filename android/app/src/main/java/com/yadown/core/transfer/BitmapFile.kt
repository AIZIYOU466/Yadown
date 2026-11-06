package com.yadown.core.transfer

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * 位图侧车文件格式：magic + version + plan 指纹 + 位图负载 + xxHash64。
 * 旧版本裸位图没有 magic，解码直接返回 null，由调用方从 DB 重建，
 * 而不是把损坏/错位的位图当权威。
 */
object BitmapFileCodec {

    private const val MAGIC = 0x59444231 // "YDB1"
    private const val VERSION = 1
    private const val HEADER_BYTES = 4 + 4 + 4 + 4 + 4 + 8

    data class Decoded(val bitmap: ChunkBitmap, val fingerprint: String)

    fun encode(bitmap: ChunkBitmap, fingerprint: String): ByteArray {
        val fingerprintBytes = fingerprint.toByteArray(Charsets.UTF_8)
        val payload = bitmap.toByteArray()

        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(fingerprintBytes.size)
            out.write(fingerprintBytes)
            out.writeInt(payload.size)
            out.write(payload)
        }
        val bodyBytes = body.toByteArray()
        val checksum = XxHash64.hash(bodyBytes)

        val result = ByteArrayOutputStream()
        DataOutputStream(result).use { out ->
            out.write(bodyBytes)
            out.writeLong(checksum)
        }
        return result.toByteArray()
    }

    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.size < HEADER_BYTES) return null
        val bodyLength = bytes.size - 8
        val body = bytes.copyOfRange(0, bodyLength)
        val expected = readLongBe(bytes, bodyLength)
        if (XxHash64.hash(body) != expected) return null

        return runCatching {
            var cursor = 0
            val magic = readIntBe(body, cursor); cursor += 4
            if (magic != MAGIC) return null
            val version = readIntBe(body, cursor); cursor += 4
            if (version != VERSION) return null
            val fingerprintLength = readIntBe(body, cursor); cursor += 4
            if (fingerprintLength < 0 || cursor + fingerprintLength > body.size) return null
            val fingerprint = String(body, cursor, fingerprintLength, Charsets.UTF_8)
            cursor += fingerprintLength
            val payloadLength = readIntBe(body, cursor); cursor += 4
            if (payloadLength < 0 || cursor + payloadLength != body.size) return null
            val payload = body.copyOfRange(cursor, cursor + payloadLength)
            val bitmap = ChunkBitmap.fromByteArray(payload)
            Decoded(bitmap, fingerprint)
        }.getOrNull()
    }

    private fun readIntBe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readLongBe(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFFL)
        }
        return value
    }
}
