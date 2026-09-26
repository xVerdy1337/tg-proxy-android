package com.tgwsproxy.proxy

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProxyStabilityTest {

    private fun server() = MtProtoProxyServer(
        appContext = RuntimeEnvironment.getApplication(),
        host = "127.0.0.1",
        port = 0,
        secret = "0123456789abcdef0123456789abcdef",
        onLog = { _, _ -> },
        onConnectionChange = {},
    )

    private fun field(target: Any, name: String) =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }

    @Test
    fun networkChangeForgetsTheDirectWaveVerdict() {
        val s = server()
        // As left behind by a lost direct probe on a network that blocks the web fronts, with the
        // backoff already at its cap.
        field(s, "directWaveFailedAt").setLong(s, System.currentTimeMillis())
        (field(s, "directWaveFailCount").get(s) as AtomicInteger).set(4)

        s.resetConnections()

        assertEquals(
            "the next session on the new network must probe direct again",
            0L, field(s, "directWaveFailedAt").getLong(s)
        )
        assertEquals(0, (field(s, "directWaveFailCount").get(s) as AtomicInteger).get())
    }

    @Test
    fun sendQueueDrainReturnsAtOnceBelowTheHighWaterMark() = runBlocking {
        var polls = 0
        awaitSendQueueDrain(queueSize = { polls++; 1000L }, alive = { true }, highWater = 4096, pollMs = 1)
        assertEquals("an unloaded queue must not cost a single wait", 1, polls)
    }

    @Test
    fun sendQueueDrainHoldsTheReaderUntilOkHttpCatchesUp() = runBlocking {
        val queued = AtomicLong(10_000)
        var waits = 0
        awaitSendQueueDrain(
            queueSize = {
                // The writer drains 2 KB between polls.
                val now = queued.get()
                if (now > 4096) { waits++; queued.addAndGet(-2048) }
                now
            },
            alive = { true },
            highWater = 4096,
            pollMs = 1,
        )
        assertTrue("reader must wait while the queue is above the mark", waits >= 3)
        assertTrue(queued.get() <= 4096)
    }

    @Test
    fun sendQueueDrainGivesUpWhenTheSessionDies() = runBlocking {
        var alive = true
        var polls = 0
        awaitSendQueueDrain(
            queueSize = { if (++polls == 3) alive = false; Long.MAX_VALUE },
            alive = { alive },
            highWater = 4096,
            pollMs = 1,
        )
        assertEquals("a closed client must not stay parked on a queue that never drains", 3, polls)
    }

    @Test
    fun highWaterMarkStaysFarBelowOkHttpsCloseThreshold() {
        // RealWebSocket closes with 1001 once more than 16 MiB is queued.
        assertTrue(WS_SEND_HIGH_WATER_BYTES * 8 <= 16L * 1024 * 1024)
    }
}
