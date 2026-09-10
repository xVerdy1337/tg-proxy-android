package com.tgwsproxy.ui

import com.tgwsproxy.R
import com.tgwsproxy.net.StrategyTester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTunePolicyTest {
    private fun strategy(command: String) = StrategyTester.Strategy(command, R.string.custom_command)

    private fun result(strategy: StrategyTester.Strategy, vararg okHosts: String) =
        StrategyTester.StrategyResult(
            strategy = strategy,
            hosts = listOf("a", "b", "c").map { host ->
                StrategyTester.HostResult(host, host in okHosts, "test")
            },
        )

    @Test fun orderingKeepsPriorityAndSortsRemaining() {
        val cached = strategy("cached")
        val saved = strategy("saved")
        val light = StrategyTester.STRATEGIES.first { it.command == "-r1+s" }
        val ordered = orderAutoTuneCandidates("cached", "saved", listOf(saved, light, cached, saved))
        assertEquals(listOf("cached", "saved", light.command), ordered.map { it.command })
    }

    @Test fun partialWinnerRequiresAtLeastSameConfirmedScore() {
        val s = strategy("candidate")
        val first = result(s, "a")
        val confirmed = result(s, "a", "b")
        val winner = confirmAutoTuneCandidate(AutoTuneWinnerState(), first, confirmed)
        assertEquals(s, winner.strategy)
        assertEquals(2, winner.okCount)
        assertTrue(winner.hostOk["b"] == true)
    }

    @Test fun weakerConfirmationPreservesPreviousWinner() {
        val old = strategy("old")
        val s = strategy("candidate")
        val previous = AutoTuneWinnerState(old, 1, mapOf("a" to true))
        val winner = confirmAutoTuneCandidate(previous, result(s, "a", "b"), result(s, "a"))
        assertEquals(previous, winner)
    }

    @Test fun refusedOrHeldEngineCannotBecomeWinner() {
        val s = strategy("candidate")
        val refused = result(s, "a").copy(engineRefused = true)
        val held = result(s, "a").copy(engineReleased = false)
        assertFalse(considerAutoTuneCandidate(AutoTuneWinnerState(), refused).second)
        assertFalse(considerAutoTuneCandidate(AutoTuneWinnerState(), held).second)
    }
}
