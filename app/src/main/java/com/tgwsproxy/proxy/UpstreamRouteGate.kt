package com.tgwsproxy.proxy

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Remembers which upstream waves and hosts failed on the current network.
 *
 * A WebSocket upgrade is not proof the front relays Telegram. Callers must
 * [noteRouteProven] only after a downstream byte, and [markDeadOpen] when a
 * host accepted the upgrade and then sent nothing. [reset] drops that memory
 * on a network change; failures recorded under an older [epoch] are ignored
 * so a probe still unwinding on the old link cannot punish the new one.
 */
internal class UpstreamRouteGate(
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val epoch = AtomicInteger(0)
    private val failCount = AtomicInteger(0)
    private val directProbeInFlight = AtomicBoolean(false)
    private val deadOpenAt = ConcurrentHashMap<String, Long>()

    @Volatile private var failedAt = 0L

    fun epoch(): Int = epoch.get()

    /**
     * Skip window for the direct wave. The shift is the current fail count, so the
     * first recorded failure already doubles the base: 4, 8, 16, then the 30 min cap.
     */
    fun negativeTtlMs(): Long = synchronized(lock) {
        val shift = failCount.get().coerceAtMost(4)
        (BASE_TTL_MS shl shift).coerceAtMost(MAX_TTL_MS)
    }

    fun directIsFresh(at: Long = now()): Boolean = synchronized(lock) {
        // failedAt == 0 means no failure is recorded. A raw subtraction would also
        // treat the first two minutes after the Unix epoch as "not fresh".
        if (failedAt == 0L) return true
        at - failedAt >= negativeTtlMs()
    }

    fun tryStartProbe(): Boolean = directProbeInFlight.compareAndSet(false, true)

    /** Release the single-flight probe only if the network has not changed since it started. */
    fun endProbe(seenEpoch: Int) {
        synchronized(lock) {
            if (epoch.get() == seenEpoch) directProbeInFlight.set(false)
        }
    }

    fun recordDirectWaveFailure(seenEpoch: Int, at: Long = now()) {
        synchronized(lock) {
            if (epoch.get() != seenEpoch) return
            failCount.incrementAndGet()
            failedAt = at
        }
    }

    /** Downstream bytes arrived. A direct proof also ends the wave backoff. */
    fun noteRouteProven(seenEpoch: Int, host: String, direct: Boolean) {
        synchronized(lock) {
            if (epoch.get() != seenEpoch) return
            deadOpenAt.remove(host)
            if (direct) {
                failedAt = 0L
                failCount.set(0)
            }
        }
    }

    fun markDeadOpen(host: String, seenEpoch: Int, at: Long = now()) {
        synchronized(lock) {
            if (epoch.get() != seenEpoch) return
            deadOpenAt[host] = at
        }
    }

    fun isDeadOpen(host: String, at: Long = now()): Boolean = synchronized(lock) {
        val marked = deadOpenAt[host] ?: return false
        if (at - marked >= DEAD_OPEN_TTL_MS) {
            deadOpenAt.remove(host, marked)
            return false
        }
        true
    }

    fun reset() {
        synchronized(lock) {
            failedAt = 0L
            failCount.set(0)
            deadOpenAt.clear()
            directProbeInFlight.set(false)
            epoch.incrementAndGet()
        }
    }

    companion object {
        const val BASE_TTL_MS = 120_000L
        const val MAX_TTL_MS = 1_800_000L
        const val DEAD_OPEN_TTL_MS = 120_000L
    }
}
