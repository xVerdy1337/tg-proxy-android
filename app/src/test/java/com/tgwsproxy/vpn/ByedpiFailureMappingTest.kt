package com.tgwsproxy.vpn

import com.tgwsproxy.R
import com.tgwsproxy.core.ByeDpiExit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DesyncVpnService.byedpiFailure] is the only place the exit-code contract with native-lib.c is
 * read, and every branch of it is a different instruction to the user. A wrong branch is not a
 * cosmetic slip: it sends someone to close SOCKS apps that were never open, or to edit a command
 * that was never the problem, while the actual holder of the engine sits inside our own process.
 *
 * The bug this file exists to keep out was exactly that. The shim used to answer a plain -1 when its
 * singleton gate refused a start, colliding with byedpi's own -1 from a failed init()/run() — and on
 * Android that one really does mean the bind lost the port. So "an auto-tune sweep still owns the
 * engine" and "port 1080 is taken" arrived indistinguishable, and the VPN picked the port story
 * every time. The codes are now disjoint, and the tests below pin both halves: that the ranges stay
 * apart, and that each one still lands on its own message.
 */
class ByedpiFailureMappingTest {

    // ---- the contract with native-lib.c ----

    @Test
    fun theShimsOwnCodesCannotCollideWithAnythingMainCanReturn() {
        // main() answers 0, -1 (init/run failed), or parse_args' status - 1. parse_args returns -1
        // for a refusal and 1 for help/version, so the whole reachable set is {0, -1, -2}. Anything
        // the shim invents has to sit outside it or the branch below it becomes unreachable.
        val fromMain = setOf(0, ByeDpiExit.MAIN_RUN_FAILED, ByeDpiExit.MAIN_BAD_COMMAND)
        assertFalse(
            "ENGINE_BUSY would be read as a byedpi run that happened",
            ByeDpiExit.ENGINE_BUSY in fromMain
        )
        assertFalse(
            "ARGV_OOM would be read as a byedpi run that happened",
            ByeDpiExit.ARGV_OOM in fromMain
        )
        assertNotEquals(
            "the two shim refusals must stay distinguishable from each other",
            ByeDpiExit.ENGINE_BUSY,
            ByeDpiExit.ARGV_OOM
        )
    }

    @Test
    fun onlyTheShimsOwnRefusalsCountAsRefusals() {
        assertTrue(ByeDpiExit.isShimRefusal(ByeDpiExit.ENGINE_BUSY))
        assertTrue(ByeDpiExit.isShimRefusal(ByeDpiExit.ARGV_OOM))
        // A run that got as far as byedpi's main() DID claim the engine and DID release it on the way
        // out. Counting either as a refusal would tell a sweep that the candidate was never tested.
        assertFalse(ByeDpiExit.isShimRefusal(ByeDpiExit.MAIN_RUN_FAILED))
        assertFalse(ByeDpiExit.isShimRefusal(ByeDpiExit.MAIN_BAD_COMMAND))
        assertFalse(ByeDpiExit.isShimRefusal(0))
    }

    // ---- the mapping ----

    @Test
    fun aRefusedStartBlamesTheEngineAndNotThePort() {
        val failure = DesyncVpnService.byedpiFailure(ByeDpiExit.ENGINE_BUSY, alive = false, port = 1080)
        assertEquals(R.string.byedpi_engine_busy, failure.res)
        // The port is not the problem, so the message must not carry one — a formatted port here is
        // how the old wording talked the user into hunting for a conflicting SOCKS app.
        assertNull("the engine-busy message takes no argument", failure.arg)
    }

    @Test
    fun aLostBindStillBlamesThePortAndNamesIt() {
        val failure = DesyncVpnService.byedpiFailure(ByeDpiExit.MAIN_RUN_FAILED, alive = false, port = 1081)
        assertEquals(R.string.byedpi_port_busy, failure.res)
        assertEquals(1081, failure.arg)
    }

    @Test
    fun aRefusedCommandIsReportedWithItsOwnExitCodeNotWithAPort() {
        val failure = DesyncVpnService.byedpiFailure(ByeDpiExit.MAIN_BAD_COMMAND, alive = false, port = 1080)
        assertEquals(R.string.byedpi_exit_code, failure.res)
        // The resource formats the code, so handing it a port would print a plausible lie.
        assertEquals(ByeDpiExit.MAIN_BAD_COMMAND, failure.arg)
    }

    @Test
    fun anUnknownNonZeroCodeFallsThroughToTheCodeMessage() {
        // Future byedpi versions may answer with codes this build has never seen. They belong in the
        // "check the command" branch rather than in any of the specific ones.
        val failure = DesyncVpnService.byedpiFailure(-7, alive = false, port = 1080)
        assertEquals(R.string.byedpi_exit_code, failure.res)
        assertEquals(-7, failure.arg)
    }

    @Test
    fun anAllocationRefusalBlamesNeitherPortNorCommand() {
        val failure = DesyncVpnService.byedpiFailure(ByeDpiExit.ARGV_OOM, alive = false, port = 1080)
        assertEquals(R.string.byedpi_start_failed_short, failure.res)
        assertNull(failure.arg)
    }

    @Test
    fun aLiveEngineThreadOutranksEveryExitCode() {
        // byedpiExitCode is written by the loop thread and read here without a barrier between them,
        // so a stale code can be visible while the thread is demonstrably still inside main(). Liveness
        // is the stronger fact: the engine came up, and it is the SOCKS handshake that is not
        // answering. Reporting "port busy" over a listener that exists sends the user to close apps
        // while their real problem is a timeout.
        val failure = DesyncVpnService.byedpiFailure(ByeDpiExit.MAIN_RUN_FAILED, alive = true, port = 1082)
        assertEquals(R.string.byedpi_socks_timeout, failure.res)
        assertEquals(1082, failure.arg)
    }

    @Test
    fun aStartThatNeverRecordedAnythingIsReportedAsNotReady() {
        // Thread dead, no code published: nothing observed the run at all, so no specific claim can be
        // made about why. Saying "not ready" is the honest floor.
        val failure = DesyncVpnService.byedpiFailure(null, alive = false, port = 1083)
        assertEquals(R.string.byedpi_socks_not_ready, failure.res)
        assertEquals(1083, failure.arg)
    }

    @Test
    fun everyBranchIsReachableAndNoTwoShareAMessage() {
        // A mapping where two inputs collapse onto one string is how the original bug looked from the
        // outside, so the distinctness is the property worth pinning, not the individual ids.
        val messages = listOf(
            DesyncVpnService.byedpiFailure(ByeDpiExit.ENGINE_BUSY, alive = false, port = 1080),
            DesyncVpnService.byedpiFailure(ByeDpiExit.ARGV_OOM, alive = false, port = 1080),
            DesyncVpnService.byedpiFailure(ByeDpiExit.MAIN_RUN_FAILED, alive = false, port = 1080),
            DesyncVpnService.byedpiFailure(ByeDpiExit.MAIN_BAD_COMMAND, alive = false, port = 1080),
            DesyncVpnService.byedpiFailure(null, alive = false, port = 1080),
            DesyncVpnService.byedpiFailure(null, alive = true, port = 1080),
        ).map { it.res }
        assertEquals(
            "each start failure must keep its own message",
            messages.size,
            messages.toSet().size
        )
    }
}
