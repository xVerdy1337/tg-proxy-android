package com.tgwsproxy.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ByedpiPresetCatalogTest {

    @Test
    fun catalogHasUniqueIdsAndCommands() {
        val all = ByedpiPresetCatalog.presets
        val runnable = ByedpiPresetCatalog.autotunePresets

        assertTrue(runnable.size >= 20)
        // Ids and commands are checked on the FULL list: a collision involving the diagnostic
        // "off" entry is invisible to an autotunePresets-only check but still corrupts byId /
        // byCommand lookups.
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(all.size, all.map { it.command }.toSet().size)
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
