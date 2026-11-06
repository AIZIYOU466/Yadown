package com.yadown.p0.incremental

import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * T0.3 低版本 APK 流式安装降级探针。
 *
 * 假设：Android 12 以下存在可接受的增量安装降级路径。
 * 只做能力探测，不执行真实安装；结论须在 API 26/28/30/31/34 真机回填。
 *
 * 依据官方 API：
 * - PackageInstaller / SessionParams（API 21+）
 * - SessionParams.setStaged()（API 31+，Build.VERSION_CODES.S）
 */
object IncrementalInstallProbe {

    data class Capability(
        val sdkInt: Int,
        val installerAvailable: Boolean,
        val stagedSupported: Boolean,
        val degradedPath: String
    )

    fun probe(context: Context): Capability {
        val installer = context.packageManager.packageInstaller
        val sdkInt = Build.VERSION.SDK_INT

        val stagedSupported = installerAvailable(installer) && sdkInt >= Build.VERSION_CODES.S &&
            runCatching { probeStaged(installer) }.getOrDefault(false)

        val degradedPath = when {
            sdkInt >= Build.VERSION_CODES.S -> "增量/暂存安装（API 31+）"
            sdkInt >= Build.VERSION_CODES.Q -> "先下载完成后安装（无增量）"
            else -> "先下载完成后安装（无增量）"
        }

        return Capability(
            sdkInt = sdkInt,
            installerAvailable = installerAvailable(installer),
            stagedSupported = stagedSupported,
            degradedPath = degradedPath
        )
    }

    private fun installerAvailable(installer: PackageInstaller): Boolean =
        runCatching {
            installer.createSession(
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            ).also { installer.abandonSession(it) }
            true
        }.getOrDefault(false)

    private fun probeStaged(installer: PackageInstaller): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setStaged()
        val sessionId = installer.createSession(params)
        installer.abandonSession(sessionId)
        return true
    }
}