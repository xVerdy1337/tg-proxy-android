package com.tgwsproxy.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpstreamRouteGateTest {

    @Test
    fun firstDirectFailureSkipsTheWaveForFourMinutes() {
        val gate = UpstreamRouteGate()
        val epoch = gate.epoch()
        val at = 1_000_000L
        assertTrue(gate.directIsFresh(at))

        gate.recordDirectWaveFailure(epoch, at)

        assertFalse(gate.directIsFresh(at + 60_000L))
        assertFalse(gate.directIsFresh(at + 239_000L))
        assertTrue(gate.directIsFresh(at + UpstreamRouteGate.BASE_TTL_MS * 2))
        assertEquals(240_000L, gate.negativeTtlMs())
    }

    @Test
    fun resetForgetsTheOldLinkAndIgnoresItsLateFailure() {
        val gate = UpstreamRouteGate()
        val epoch = gate.epoch()
        gate.recordDirectWaveFailure(epoch, 10_000L)
        gate.markDeadOpen("kws.example", epoch, 10_000L)

        gate.reset()
        gate.recordDirectWaveFailure(epoch, 20_000L)
        gate.markDeadOpen("kws.example", epoch, 20_000L)

        assertEquals(epoch + 1, gate.epoch())
        assertTrue(gate.directIsFresh(20_000L))
        assertFalse(gate.isDeadOpen("kws.example", 20_000L))
    }

    @Test
    fun staleProofDoesNotClearAFailureOnTheNewNetwork() {
        val gate = UpstreamRouteGate()
        val old = gate.epoch()
        gate.reset()
        val newer = gate.epoch()
        gate.recordDirectWaveFailure(newer, 50_000L)
        gate.markDeadOpen("kws.example", newer, 50_000L)

        gate.noteRouteProven(old, "kws.example", direct = true)

        assertFalse(gate.directIsFresh(50_000L))
        assertTrue(gate.isDeadOpen("kws.example", 50_000L))

        gate.noteRouteProven(newer, "kws.example", direct = true)

        assertTrue(gate.directIsFresh(50_000L))
        assertFalse(gate.isDeadOpen("kws.example", 51_000L))
    }

    @Test
    fun cloudflareProofDoesNotPretendDirectIsHealthy() {
        val gate = UpstreamRouteGate()
        val epoch = gate.epoch()
        gate.recordDirectWaveFailure(epoch, 10_000L)

        gate.noteRouteProven(epoch, "edge.example", direct = false)

        assertFalse(gate.directIsFresh(10_000L))
        assertFalse(gate.isDeadOpen("edge.example", 10_000L))
    }

    @Test
    fun deadOpenExpiresAndAStaleProbeCannotDropTheNewFlag() {
        val gate = UpstreamRouteGate()
        val epoch = gate.epoch()
        val at = 5_000L
        gate.markDeadOpen("dead.example", epoch, at)
        assertTrue(gate.isDeadOpen("dead.example", at + 1_000L))
        assertFalse(gate.isDeadOpen("dead.example", at + UpstreamRouteGate.DEAD_OPEN_TTL_MS))

        assertTrue(gate.tryStartProbe())
        assertFalse(gate.tryStartProbe())
        gate.reset()
        assertTrue(gate.tryStartProbe())
        gate.endProbe(epoch)
        assertFalse(gate.tryStartProbe())
        gate.endProbe(gate.epoch())
        assertTrue(gate.tryStartProbe())
    }
}
