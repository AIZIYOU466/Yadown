package com.yadown.core.survival

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryGuidanceTest {

    @Test
    fun everyRomHasActionableSteps() {
        RomType.values().forEach { rom ->
            val guidance = BatteryGuidance.guidance(rom)
            assertEquals(rom, guidance.rom)
            assertTrue("${rom.name} 缺少引导步骤", guidance.steps.isNotEmpty())
            assertTrue("${rom.name} 缺少标题", guidance.title.isNotBlank())
        }
    }

    @Test
    fun domesticRomsPointAtTheirOwnSettingsPage() {
        assertNotNull(BatteryGuidance.guidance(RomType.HYPEROS).settingsPackage)
        assertNotNull(BatteryGuidance.guidance(RomType.EMUI).settingsPackage)
        assertNotNull(BatteryGuidance.guidance(RomType.COLOROS).settingsPackage)
        assertNotNull(BatteryGuidance.guidance(RomType.ORIGINOS).settingsPackage)
    }

    @Test
    fun aospFallsBackToSystemSettings() {
        val guidance = BatteryGuidance.guidance(RomType.AOSP)
        // 原生 Android 没有厂商自启动页，调用方会回退到应用详情页。
        assertNull(guidance.settingsPackage)
        assertNull(guidance.settingsClass)
    }

    @Test
    fun miuiAndHyperosShareTheSameEntry() {
        val miui = BatteryGuidance.guidance(RomType.MIUI)
        val hyperos = BatteryGuidance.guidance(RomType.HYPEROS)
        assertEquals(miui.settingsPackage, hyperos.settingsPackage)
        assertEquals(miui.settingsClass, hyperos.settingsClass)
    }

    @Test
    fun romSpecificPackagesAreStable() {
        assertEquals(
            "com.miui.securitycenter",
            BatteryGuidance.guidance(RomType.HYPEROS).settingsPackage
        )
        assertEquals(
            "com.huawei.systemmanager",
            BatteryGuidance.guidance(RomType.EMUI).settingsPackage
        )
    }
}
