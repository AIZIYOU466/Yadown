package com.yadown.core.survival

import org.junit.Assert.assertEquals
import org.junit.Test

class LifecycleManagerTest {

    private fun ctx(
        userInitiated: Boolean = false,
        appForeground: Boolean = false,
        uidt: Boolean = false,
        deferrable: Boolean = false,
        inDoze: Boolean = false
    ) = LifecycleContext(userInitiated, appForeground, uidt, deferrable, inDoze)

    @Test
    fun userInitiatedForeground_usesForegroundService() {
        assertEquals(
            Carrier.FOREGROUND_SERVICE,
            LifecycleManager.selectCarrier(ctx(userInitiated = true, appForeground = true))
        )
    }

    @Test
    fun userInitiatedBackgroundWithUidt_usesUidt() {
        assertEquals(
            Carrier.UIDT,
            LifecycleManager.selectCarrier(ctx(userInitiated = true, uidt = true))
        )
    }

    @Test
    fun userInitiatedBackgroundNoUidt_usesForegroundService() {
        assertEquals(
            Carrier.FOREGROUND_SERVICE,
            LifecycleManager.selectCarrier(ctx(userInitiated = true))
        )
    }

    @Test
    fun deferrableNotUserInitiated_usesWorkManager() {
        assertEquals(
            Carrier.WORK_MANAGER,
            LifecycleManager.selectCarrier(ctx(deferrable = true))
        )
    }

    @Test
    fun deferrableButUserInitiated_prefersForegroundService() {
        assertEquals(
            Carrier.FOREGROUND_SERVICE,
            LifecycleManager.selectCarrier(ctx(userInitiated = true, deferrable = true))
        )
    }

    @Test
    fun inDoze_takesPrecedence() {
        assertEquals(
            Carrier.DOZE_STOPPED,
            LifecycleManager.selectCarrier(ctx(userInitiated = true, inDoze = true))
        )
        assertEquals(
            Carrier.DOZE_STOPPED,
            LifecycleManager.selectCarrier(ctx(deferrable = true, inDoze = true))
        )
    }

    @Test
    fun otherwise_usesForegroundService() {
        assertEquals(
            Carrier.FOREGROUND_SERVICE,
            LifecycleManager.selectCarrier(ctx())
        )
    }
}