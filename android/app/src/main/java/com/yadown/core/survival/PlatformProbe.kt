package com.yadown.core.survival

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import java.net.InetAddress

/** 存储能力快照（T2.1）。 */
data class StorageCapability(
    val totalBytes: Long,
    val freeBytes: Long,
    val lowStorage: Boolean
)

/** 网络可达性快照（T2.1）。 */
data class Reachability(
    val connected: Boolean,
    val unmetered: Boolean,
    val validated: Boolean
)

/**
 * PlatformAdapter 的运行时实现（docs/ARCHITECTURE.md §5.4）。
 *
 * 只读探测，不做任何权限申请；调用方据此选择 Carrier 与写入策略。
 */
object PlatformProbe {

    /**
     * Android 15 (API 35) 起提供 User-installed Downloads Timer：
     * dataSync 前台服务满足用户主动下载的 UIDT 约束，后台下载不被掐断。
     */
    fun isUidtSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA

    fun detectRom(): RomType = RomDetector.detect(Build.MANUFACTURER)

    /** 以应用私有下载目录所在分区为准：随机写落在该分区，容量判断才有意义。 */
    fun probeStorageCapability(context: Context): StorageCapability {
        val stat = StatFs(context.filesDir.absolutePath)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        return StorageCapability(
            totalBytes = total,
            freeBytes = free,
            lowStorage = total > 0 && free.toDouble() / total < LOW_STORAGE_RATIO
        )
    }

    /**
     * 先看系统是否认为链路可用，再做一次 DNS 解析确认目标主机可达。
     * DNS 会阻塞，必须在 IO 线程调用。
     */
    fun probeNetworkReachability(context: Context, host: String): Reachability {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager?.getNetworkCapabilities(manager.activeNetwork)
        val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        if (!connected || host.isBlank()) {
            return Reachability(connected = false, unmetered = unmetered, validated = validated)
        }
        val resolvable = runCatching { InetAddress.getByName(host) }.isSuccess
        return Reachability(
            connected = resolvable,
            unmetered = unmetered,
            validated = validated && resolvable
        )
    }

    /**
     * 存储加密状态。
     *
     * 公开 API 没有「是否启用 inline encryption（ICE）」的查询入口；
     * `DevicePolicyManager.getStorageEncryptionStatus()` 是唯一公开的加密状态信号，
     * 对 FBE 设备返回 ACTIVE*。因此这里返回「存储加密是否生效」，
     * 不等于 ICE 能力存在，命名沿用契约但语义以此为准。
     */
    fun probeInlineEncryption(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        return dpm.storageEncryptionStatus in ACTIVE_ENCRYPTION_STATES
    }

    private val ACTIVE_ENCRYPTION_STATES = setOf(
        DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE,
        DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER,
        DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY
    )

    private const val LOW_STORAGE_RATIO = 0.10
}
