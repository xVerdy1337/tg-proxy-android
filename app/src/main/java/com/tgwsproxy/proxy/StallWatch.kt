package com.tgwsproxy.proxy

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the stall watchdog should do next. [Park] suspends until the client sends
 * again and arms no timer, so an idle MTProto session does not wake the CPU.
 */
internal sealed class StallWait {
    data object Trip : StallWait()
    data object Park : StallWait()
    data class Sleep(val ms: Long) : StallWait()
}

internal data class StallCursor(
    val confirmations: Int = 0,
    val busyAt: Long,
    val firstByteChecked: Boolean = false,
)

internal data class StallDecision(
    val wait: StallWait,
    val cursor: StallCursor,
)

internal data class StallSample(
    val upAt: Long,
    val downAt: Long,
    val connUp: Long,
    val connDown: Long,
    val queueBytes: Long,
)

/**
 * One step of the route stall watchdog.
 *
 * The evidence rules live here so a timer is armed only when it can still change
 * the verdict. An idle session, and an ack whose recency window cannot fit two
 * confirmations, parks instead of polling.
 */
internal fun stallDecision(
    cursor: StallCursor,
    now: Long,
    sessionStart: Long,
    upAt: Long,
    downAt: Long,
    connUp: Long,
    connDown: Long,
    queueBytes: Long,
    firstByteMs: Long,
    silenceMs: Long,
    pollMs: Long,
    confirmationsNeeded: Int,
): StallDecision {
    if (!cursor.firstByteChecked) {
        val due = sessionStart + firstByteMs
        if (now < due) return StallDecision(StallWait.Sleep(due - now), cursor)
        val checked = cursor.copy(firstByteChecked = true)
        if (connDown == 0L && connUp > 0L && queueBytes == 0L) {
            return StallDecision(StallWait.Trip, checked)
        }
        return stallDecision(
            checked, now, sessionStart, upAt, downAt, connUp, connDown, queueBytes,
            firstByteMs, silenceMs, pollMs, confirmationsNeeded,
        )
    }
    if (queueBytes > 0L) {
        // Still draining. Silence measured from now would blame a slow uplink.
        return StallDecision(
            StallWait.Sleep(pollMs),
            cursor.copy(confirmations = 0, busyAt = now),
        )
    }
    val silenceFrom = maxOf(downAt, cursor.busyAt)
    val quiet = upAt > downAt &&
        now - silenceFrom >= silenceMs &&
        now - upAt <= silenceMs
    if (quiet) {
        val nextConfirmations = cursor.confirmations + 1
        if (nextConfirmations >= confirmationsNeeded) {
            return StallDecision(StallWait.Trip, cursor.copy(confirmations = nextConfirmations))
        }
        val windowLeft = silenceMs - (now - upAt)
        return if (windowLeft >= pollMs) {
            StallDecision(StallWait.Sleep(pollMs), cursor.copy(confirmations = nextConfirmations))
        } else {
            // The second sample cannot land while the demand is still recent.
            StallDecision(StallWait.Park, cursor.copy(confirmations = 0))
        }
    }
    val cleared = cursor.copy(confirmations = 0)
    if (upAt > downAt) {
        val silenceDue = silenceFrom + silenceMs
        val demandExpires = upAt + silenceMs
        if (silenceDue <= demandExpires && silenceDue > now) {
            return StallDecision(StallWait.Sleep(silenceDue - now), cleared)
        }
    }
    return StallDecision(StallWait.Park, cleared)
}

/** Conflated poke from the upload loop. A parked watchdog waits on this and holds no timer. */
internal class StallKick {
    private val channel = Channel<Unit>(Channel.CONFLATED)

    fun arm() {
        channel.trySend(Unit)
    }

    suspend fun park() {
        channel.receive()
    }

    /** True when the client sent bytes before [timeoutMs] elapsed. */
    suspend fun await(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            channel.receive()
            true
        } ?: false
}

internal suspend fun runStallWatch(
    kick: StallKick,
    sessionStart: Long,
    now: () -> Long,
    sample: () -> StallSample,
    onTrip: () -> Unit,
    skipFirstByte: Boolean,
    firstByteMs: Long,
    silenceMs: Long,
    pollMs: Long,
    confirmationsNeeded: Int,
) {
    var cursor = StallCursor(busyAt = sessionStart, firstByteChecked = skipFirstByte)
    while (currentCoroutineContext().isActive) {
        val s = sample()
        val decision = stallDecision(
            cursor, now(), sessionStart, s.upAt, s.downAt, s.connUp, s.connDown, s.queueBytes,
            firstByteMs, silenceMs, pollMs, confirmationsNeeded,
        )
        cursor = decision.cursor
        when (val wait = decision.wait) {
            StallWait.Trip -> {
                onTrip()
                return
            }
            StallWait.Park -> kick.park()
            is StallWait.Sleep -> {
                // A client byte invalidates the open confirmation. Recompute; do not trip
                // on the poke itself.
                if (kick.await(wait.ms)) cursor = cursor.copy(confirmations = 0)
            }
        }
    }
}
