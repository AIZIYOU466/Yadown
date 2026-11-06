package com.yadown.core.survival

enum class RomType { EMUI, HYPEROS, MIUI, COLOROS, ORIGINOS, AOSP, UNKNOWN }

object RomDetector {
    /**
     * 按厂商推断 ROM 家族。注意厂商名并不总能精确区分 MIUI/HyperOS，
     * 这里把小米统一归到 HyperOS（当前主推），真机若需区分可再加 Build 特征探测。
     */
    fun detect(manufacturer: String): RomType {
        val m = manufacturer.trim().lowercase()
        return when {
            m == "huawei" || m == "honor" -> RomType.EMUI
            m == "xiaomi" -> RomType.HYPEROS
            m == "oppo" || m == "oneplus" -> RomType.COLOROS
            m == "vivo" -> RomType.ORIGINOS
            m.isEmpty() || m == "unknown" -> RomType.UNKNOWN
            else -> RomType.AOSP
        }
    }
}