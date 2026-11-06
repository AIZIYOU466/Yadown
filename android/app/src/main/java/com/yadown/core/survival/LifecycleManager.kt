package com.yadown.core.survival

enum class Carrier { FOREGROUND_SERVICE, UIDT, WORK_MANAGER, DOZE_STOPPED }

data class LifecycleContext(
    val userInitiated: Boolean,
    val appForeground: Boolean,
    val uidtAvailable: Boolean,
    val deferrable: Boolean,
    val inDoze: Boolean
)

/**
 * 生命周期 Carrier 选择，规则见 docs/ARCHITECTURE.md §5.4。
 * Doze 优先级最高；可延迟的后台补齐走 WorkManager。
 */
object LifecycleManager {
    fun selectCarrier(ctx: LifecycleContext): Carrier = when {
        ctx.inDoze -> Carrier.DOZE_STOPPED
        !ctx.userInitiated && ctx.deferrable -> Carrier.WORK_MANAGER
        ctx.userInitiated -> when {
            ctx.appForeground -> Carrier.FOREGROUND_SERVICE
            ctx.uidtAvailable -> Carrier.UIDT
            else -> Carrier.FOREGROUND_SERVICE
        }
        else -> Carrier.FOREGROUND_SERVICE
    }
}