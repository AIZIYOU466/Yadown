package com.yadown.core.transfer

import java.security.MessageDigest

/**
 * 下载计划的指纹：位图、DB 分块与文件偏移必须绑定同一份计划。
 * 任一分块参数变化都会让旧位图失效，避免“旧位图套新计划”的静默错位。
 */
object PlanFingerprint {

    fun of(totalSize: Long, chunkSize: Long, planVersion: Int = 1): String {
        require(totalSize > 0) { "totalSize 必须为正: $totalSize" }
        require(chunkSize > 0) { "chunkSize 必须为正: $chunkSize" }
        val payload = "$planVersion:$totalSize:$chunkSize".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(HEX[value ushr 4])
                append(HEX[value and 0x0F])
            }
        }
    }

    private const val HEX = "0123456789abcdef"
}
