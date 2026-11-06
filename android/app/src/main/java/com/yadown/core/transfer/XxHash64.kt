package com.yadown.core.transfer

/**
 * xxHash64（seed 可配置），用于分块内容校验。
 *
 * 选择自实现而不是新增依赖：算法小、可测，且分块校验属于热路径，
 * 不需要为一个哈希函数引入额外运行时。
 */
object XxHash64 {

    private const val PRIME1 = -7046029288634856825L
    private const val PRIME2 = -4417276706812531889L
    private const val PRIME3 = 1609587929392839161L
    private const val PRIME4 = -8796714831421723037L
    private const val PRIME5 = 2870177450012600261L

    fun hash(bytes: ByteArray, seed: Long = 0L): Long =
        hash(bytes, 0, bytes.size, seed)

    fun hash(bytes: ByteArray, offset: Int, length: Int, seed: Long = 0L): Long {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "区间越界: offset=$offset length=$length size=${bytes.size}"
        }
        var index = offset
        val end = offset + length
        var hash: Long

        if (length >= 32) {
            var v1 = seed + PRIME1 + PRIME2
            var v2 = seed + PRIME2
            var v3 = seed
            var v4 = seed - PRIME1

            val limit = end - 32
            while (index <= limit) {
                v1 = round(v1, readLongLe(bytes, index))
                v2 = round(v2, readLongLe(bytes, index + 8))
                v3 = round(v3, readLongLe(bytes, index + 16))
                v4 = round(v4, readLongLe(bytes, index + 24))
                index += 32
            }

            hash = rotl(v1, 1) + rotl(v2, 7) + rotl(v3, 12) + rotl(v4, 18)
            hash = mergeRound(hash, v1)
            hash = mergeRound(hash, v2)
            hash = mergeRound(hash, v3)
            hash = mergeRound(hash, v4)
        } else {
            hash = seed + PRIME5
        }

        hash += length.toLong()

        while (index + 8 <= end) {
            val k1 = round(0L, readLongLe(bytes, index))
            hash = hash xor k1
            hash = rotl(hash, 27) * PRIME1 + PRIME4
            index += 8
        }

        if (index + 4 <= end) {
            hash = hash xor (readIntLe(bytes, index).toLong() * PRIME1)
            hash = rotl(hash, 23) * PRIME2 + PRIME3
            index += 4
        }

        while (index < end) {
            hash = hash xor ((bytes[index].toLong() and 0xFFL) * PRIME5)
            hash = rotl(hash, 11) * PRIME1
            index++
        }

        hash = hash xor (hash ushr 33)
        hash *= PRIME2
        hash = hash xor (hash ushr 29)
        hash *= PRIME3
        hash = hash xor (hash ushr 32)
        return hash
    }

    private fun round(acc: Long, input: Long): Long =
        rotl(acc + input * PRIME2, 31) * PRIME1

    private fun mergeRound(acc: Long, value: Long): Long {
        val mixed = acc xor round(0L, value)
        return mixed * PRIME1 + PRIME4
    }

    private fun rotl(value: Long, bits: Int): Long =
        (value shl bits) or (value ushr (64 - bits))

    private fun readLongLe(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = value or ((bytes[offset + i].toLong() and 0xFFL) shl (8 * i))
        }
        return value
    }

    private fun readIntLe(bytes: ByteArray, offset: Int): Int {
        var value = 0
        for (i in 0 until 4) {
            value = value or ((bytes[offset + i].toInt() and 0xFF) shl (8 * i))
        }
        return value
    }
}
