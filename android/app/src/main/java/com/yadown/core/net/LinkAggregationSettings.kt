package com.yadown.core.net

import android.content.Context

/** 多链路加速开关。默认关闭；允许移动数据默认开启，但 UI 必须提示流量消耗。 */
class LinkAggregationSettings(context: Context) {

    private val prefs = context.getSharedPreferences("link_aggregation", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    var allowMetered: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_METERED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ALLOW_METERED, value).apply()
        }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_ALLOW_METERED = "allow_metered"
    }
}
