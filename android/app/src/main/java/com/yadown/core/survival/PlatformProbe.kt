package com.yadown.core.survival

import android.os.Build

object PlatformProbe {
    /**
     * Android 15 (API 35) 起提供 User-installed Downloads Timer：
     * dataSync 前台服务满足用户主动下载的 UIDT 约束，后台下载不被掐断。
     */
    fun isUidtSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA

    fun detectRom(): RomType = RomDetector.detect(Build.MANUFACTURER)
}