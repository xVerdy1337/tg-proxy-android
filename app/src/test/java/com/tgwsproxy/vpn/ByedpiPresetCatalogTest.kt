package com.tgwsproxy.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ByedpiPresetCatalogTest {

    @Test
    fun catalogHasUniqueIdsAndCommands() {
        val runnable = ByedpiPresetCatalog.autotunePresets

        assertTrue(runnable.size >= 20)
        assertEquals(runnable.size, runnable.map { it.id }.toSet().size)
        assertEquals(runnable.size, runnable.map { it.command }.toSet().size)
        assertTrue(runnable.all { it.command.isNotBlank() })
    }

    @Test
    fun builtInDefaultsComeFromCatalog() {
        assertEquals(
            ByedpiPresetCatalog.byId(DesyncVpnService.PRESET_AUTO)?.command,
            ByedpiPresetCatalog.commandFor(DesyncVpnService.PRESET_AUTO),
        )
        assertEquals(
            ByedpiPresetCatalog.byId(DesyncVpnService.PRESET_OFF)?.command,
            ByedpiPresetCatalog.commandFor(DesyncVpnService.PRESET_OFF),
        )
        assertFalse(ByedpiPresetCatalog.commandFor("unknown").isBlank())
    }
}
