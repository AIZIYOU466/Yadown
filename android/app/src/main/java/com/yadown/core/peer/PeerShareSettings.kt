package com.yadown.core.peer

import android.content.Context
import android.os.Build

/** 局域网加速总开关与设备名。默认关闭，用户显式开启后才启动服务端与发现。 */
class PeerShareSettings(context: Context) {

    private val prefs = context.getSharedPreferences("peer_share", Context.MODE_PRIVATE)

    val enabled: Boolean get() = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    val deviceName: String
        get() = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: defaultName()

    private fun defaultName(): String =
        Build.MODEL?.takeIf { it.isNotBlank() } ?: "Yadown 设备"

    private companion object {
        const val KEY_ENABLED = "lan_acceleration_enabled"
        const val KEY_NAME = "device_name"
    }
}
