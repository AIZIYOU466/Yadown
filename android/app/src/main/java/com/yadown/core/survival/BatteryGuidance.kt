package com.yadown.core.survival

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * ROM 感知的后台保活引导（T2.3）。
 *
 * 国产 ROM 会额外掐断后台服务，单纯申请电池优化白名单不足以让下载存活。
 * 这里按厂商给出「自启动 / 后台耗电」设置页组件名，用户点一下直达对应页面；
 * 组件名可能随 ROM 版本变化，因此在 [settingsIntent] 中校验可解析性，
 * 不可解析时回退到应用详情页，不猜测也不静默失败。
 */
data class BackgroundGuidance(
    val rom: RomType,
    val title: String,
    val steps: List<String>,
    val settingsPackage: String?,
    val settingsClass: String?
)

object BatteryGuidance {

    fun guidance(rom: RomType): BackgroundGuidance = when (rom) {
        RomType.HYPEROS, RomType.MIUI -> BackgroundGuidance(
            rom = rom,
            title = "小米 / HyperOS 后台保活",
            steps = listOf(
                "设置 → 应用设置 → 授权管理 → 自启动：允许 Yadown",
                "设置 → 应用管理 → Yadown → 省电策略：无限制",
                "最近任务卡片下拉，锁定 Yadown"
            ),
            settingsPackage = "com.miui.securitycenter",
            settingsClass = "com.miui.permcenter.autostart.AutoStartManagementActivity"
        )
        RomType.EMUI -> BackgroundGuidance(
            rom = rom,
            title = "华为 / 荣耀后台保活",
            steps = listOf(
                "设置 → 应用 → 应用启动管理 → Yadown：改为手动管理，全部允许",
                "设置 → 电池 → 更多电池设置：关闭「休眠时始终保持网络连接」限制",
                "任务管理器里给 Yadown 加锁"
            ),
            settingsPackage = "com.huawei.systemmanager",
            settingsClass = "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        )
        RomType.COLOROS -> BackgroundGuidance(
            rom = rom,
            title = "OPPO / ColorOS 后台保活",
            steps = listOf(
                "设置 → 电池 → 应用耗电管理 → Yadown：允许后台运行、允许自启动",
                "设置 → 应用管理 → Yadown → 耗电管理：不限制"
            ),
            settingsPackage = "com.coloros.safecenter",
            settingsClass = "com.coloros.safecenter.permission.startup.StartupAppListActivity"
        )
        RomType.ORIGINOS -> BackgroundGuidance(
            rom = rom,
            title = "vivo / OriginOS 后台保活",
            steps = listOf(
                "设置 → 电池 → 后台耗电管理 → Yadown：允许后台高耗电",
                "i管家 → 应用管理 → 自启动：允许 Yadown"
            ),
            settingsPackage = "com.vivo.permissionmanager",
            settingsClass = "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
        )
        RomType.AOSP, RomType.UNKNOWN -> BackgroundGuidance(
            rom = rom,
            title = "后台运行设置",
            steps = listOf("设置 → 应用 → Yadown → 电池：改为「不受限制」"),
            settingsPackage = null,
            settingsClass = null
        )
    }

    /** 直达 ROM 的自启动/后台管理页；组件不可用时回退到系统应用详情页。 */
    fun settingsIntent(context: Context, guidance: BackgroundGuidance): Intent {
        val explicit = guidance.settingsPackage?.let { pkg ->
            guidance.settingsClass?.let { cls ->
                Intent().setClassName(pkg, cls)
            }
        }
        if (explicit != null && context.packageManager.resolveActivity(explicit, 0) != null) {
            return explicit
        }
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
    }

    /** 电池优化白名单请求；这是系统允许应用为自己拉起的唯一免优化入口。 */
    fun batteryOptimizationIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.fromParts("package", context.packageName, null)
        )
}
