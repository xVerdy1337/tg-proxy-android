package com.tgwsproxy.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StallWatchTest {

    private val firstByte = 9_000L
    private val silence = 90_000L
    private val poll = 15_000L

    private fun decide(
        cursor: StallCursor,
        now: Long,
        upAt: Long,
        downAt: Long,
        connUp: Long = 1,
        connDown: Long = 0,
        queueBytes: Long = 0,
        sessionStart: Long = 0,
    ) = stallDecision(
        cursor, now, sessionStart, upAt, downAt, connUp, connDown, queueBytes,
        firstByte, silence, poll, confirmationsNeeded = 2,
    )

    @Test
    fun firstByteCheckWaitsThenTripsABlackholedOpen() {
        val start = StallCursor(busyAt = 0)
        val waiting = decide(start, now = 1_000, upAt = 500, downAt = 0, connUp = 20)
        val sleep = assertIs<StallWait.Sleep>(waiting.wait)
        assertEquals(8_000L, sleep.ms)
        assertEquals(false, waiting.cursor.firstByteChecked)

        val dead = decide(start, now = 9_000, upAt = 500, downAt = 0, connUp = 20)
        assertIs<StallWait.Trip>(dead.wait)
    }

    @Test
    fun backlogAtTheFirstCheckDoesNotTrip() {
        val decision = decide(
            StallCursor(busyAt = 0),
            now = 9_000,
            upAt = 100,
            downAt = 0,
            connUp = 50_000,
            queueBytes = 50_000,
        )
        assertIs<StallWait.Sleep>(decision.wait)
        assertEquals(9_000L, decision.cursor.busyAt)
        assertTrue(decision.cursor.firstByteChecked)
        assertEquals(0, decision.cursor.confirmations)
    }

    @Test
    fun idleAckParksInsteadOfPolling() {
        // up trails its own container by a millisecond. The recency window cannot
        // fit a second sample, which is what used to evict healthy media sessions.
        val cursor = StallCursor(busyAt = 0, firstByteChecked = true)
        val decision = decide(cursor, now = 90_900, upAt = 1_000, downAt = 900, connDown = 10)
        assertIs<StallWait.Park>(decision.wait)
        assertEquals(0, decision.cursor.confirmations)
    }

    @Test
    fun quietSessionWithNoOutstandingDemandParks() {
        val cursor = StallCursor(busyAt = 0, firstByteChecked = true)
        val decision = decide(cursor, now = 200_000, upAt = 1_000, downAt = 1_000, connDown = 10)
        assertIs<StallWait.Park>(decision.wait)
    }

    @Test
    fun unansweredSendSleepsUntilSilenceCanBeReal() {
        val cursor = StallCursor(busyAt = 0, firstByteChecked = true)
        val decision = decide(cursor, now = 5_000, upAt = 5_000, downAt = 0, connDown = 8)
        val sleep = assertIs<StallWait.Sleep>(decision.wait)
        assertEquals(85_000L, sleep.ms)
    }

    @Test
    fun deadRouteNeedsTwoConfirmations() {
        val cursor = StallCursor(busyAt = 0, firstByteChecked = true)
        val first = decide(cursor, now = 90_000, upAt = 80_000, downAt = 0)
        assertIs<StallWait.Sleep>(first.wait)
        assertEquals(poll, (first.wait as StallWait.Sleep).ms)
        assertEquals(1, first.cursor.confirmations)

        val second = decide(first.cursor, now = 105_000, upAt = 80_000, downAt = 0)
        assertIs<StallWait.Trip>(second.wait)
    }
}
