package com.yadown.p0.incremental

import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * T0.3 低版本 APK 流式安装降级探针。
 *
 * 公开 API 事实（已核实：
 * https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams）：
 * - `SessionParams.setStaged()` 是 `@SystemApi @TestApi @hide`，普通应用无法调用；
 * - 公开可读的是 `PackageInstaller.Session.isStaged()`（API 29+）。
 *
 * 因此本探针只判断「普通应用能否创建安装 session」，并给出降级路径；
 * 不声称能强制 staged/incremental install。
 */
object IncrementalInstallProbe {

    data class Capability(
        val sdkInt: Int,
        val installerAvailable: Boolean,
        val stagedReadable: Boolean,
        val degradedPath: String
    )

    fun probe(context: Context): Capability {
        val installer = context.packageManager.packageInstaller
        val sdkInt = Build.VERSION.SDK_INT
        val installerAvailable = installerAvailable(installer)
        val stagedReadable = installerAvailable &&
            sdkInt >= Build.VERSION_CODES.Q &&
            runCatching { readStaged(installer) }.getOrDefault(false)

        val degradedPath = when {
            !installerAvailable -> "无法使用 PackageInstaller，走系统安装器 Intent"
            sdkInt >= Build.VERSION_CODES.S ->
                "普通应用无法强制 staged，走「先下载完成后安装」；系统应用可走增量安装"
            else -> "先下载完成后安装（无增量）"
        }

        return Capability(
            sdkInt = sdkInt,
            installerAvailable = installerAvailable,
            stagedReadable = stagedReadable,
            degradedPath = degradedPath
        )
    }

    private fun installerAvailable(installer: PackageInstaller): Boolean =
        runCatching {
            val id = installer.createSession(
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            )
            installer.abandonSession(id)
            true
        }.getOrDefault(false)

    private fun readStaged(installer: PackageInstaller): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val id = installer.createSession(
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        )
        return try {
            installer.openSession(id).use { it.isStaged }
        } finally {
            installer.abandonSession(id)
        }
    }
}
