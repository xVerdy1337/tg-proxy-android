package com.tgwsproxy.service

import android.content.Context
import android.content.Intent
import com.tgwsproxy.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.BindException
import java.net.ServerSocket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProxyServiceFailureTest {

    @Test
    fun failedStartStopsTheForegroundService() {
        occupyDefaultPort().use {
            val intent = Intent().setAction(ProxyService.ACTION_START)
            val controller = Robolectric.buildService(ProxyService::class.java, intent).create()
            val service = controller.get()

            controller.startCommand(0, 1)

            assertTrue("proxy startup did not fail in time", await {
                !service.serviceState.value.isRunning
            })
            assertTrue("failed startup must stop the service", await {
                shadowOf(service).isStoppedBySelf()
            })
            // The refusal has to survive as state, not only as a log line: the hero renders
            // ServiceState.error, and without it a refused start looks exactly like never having
            // pressed the button. Pinned to the exact string so the BindException branch cannot
            // quietly start reporting the generic "try again" message.
            assertEquals(
                "the busy port must be explained in state",
                service.getString(R.string.proxy_error_port_busy, ProxyService.DEFAULT_PORT),
                service.serviceState.value.error
            )
        }
    }

    @Test
    fun malformedSecretIsExplainedInsteadOfCrashing() {
        val intent = Intent().setAction(ProxyService.ACTION_START)
        val controller = Robolectric.buildService(ProxyService::class.java, intent).create()
        val service = controller.get()

        // Overwrite the secret AFTER onCreate (which generates a valid one) but BEFORE the
        // start: startProxy re-reads the persisted value, so the server is built from this one.
        // Contract under test (MtProtoProxyServer.parseSecret): a persisted secret that is not
        // valid even-length hex must fail the start with proxy_error_bad_secret — a crash or a
        // silently running proxy would both pass an assertion that only checked "not running".
        service.getSharedPreferences(ProxyService.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(ProxyService.KEY_SECRET, "abc") // odd length: not decodable hex
            .commit()

        controller.startCommand(0, 1)

        assertTrue("proxy startup did not fail in time", await {
            !service.serviceState.value.isRunning
        })
        assertTrue("failed startup must stop the service", await {
            shadowOf(service).isStoppedBySelf()
        })
        assertEquals(
            "a broken saved secret must be explained in state",
            service.getString(R.string.proxy_error_bad_secret),
            service.serviceState.value.error
        )
    }

    @Test
    fun startThatSucceedsRetiresThePreviousFailure() {
        val intent = Intent().setAction(ProxyService.ACTION_START)
        val controller = Robolectric.buildService(ProxyService::class.java, intent).create()
        val service = controller.get()

        try {
            occupyDefaultPort().use {
                controller.startCommand(0, 1)
                assertTrue("proxy startup did not fail in time", await {
                    service.serviceState.value.error != null
                })
            }

            // Port released, same service instance: the message told the user to start again, so
            // starting again has to clear it instead of parking a stale complaint under a proxy
            // that is now up.
            controller.startCommand(0, 2)
            assertTrue("proxy did not bind once the port was free", await {
                service.serviceState.value.logs.any { it.text.contains(listeningLine(service)) }
            })
            assertNull(
                "a start that worked must clear the previous failure",
                service.serviceState.value.error
            )
            assertTrue("a bound proxy must report itself running", service.serviceState.value.isRunning)
        } finally {
            releaseListener(service)
        }
    }

    private fun listeningLine(service: ProxyService): String =
        service.getString(R.string.proxy_listening, ProxyService.DEFAULT_HOST, ProxyService.DEFAULT_PORT)

    /**
     * REQUIREMENT: these tests provoke the service's real BindException path, and the main code
     * binds the fixed constant [ProxyService.DEFAULT_PORT] with no seam to inject a port — so the
     * test itself must hold that port first. If the machine running the suite already has
     * something on it (another proxy, a leftover process), the busy-port scenario cannot be
     * reproduced here; skip via Assume instead of failing on the environment.
     */
    private fun occupyDefaultPort(): ServerSocket = try {
        ServerSocket(ProxyService.DEFAULT_PORT)
    } catch (e: BindException) {
        assumeNoException(
            "port ${ProxyService.DEFAULT_PORT} is already held on this machine; skipping",
            e
        )
        error("unreachable: assumeNoException always throws")
    }

    /**
     * Hand ACTION_STOP straight to the service — the controller was built with the start intent and
     * this needs the listener closed, not another lifecycle step. Without it the accept loop keeps
     * port 1443 for the rest of the JVM and every later bind in this suite fails — so the wait for
     * the stop is asserted, not just performed.
     */
    private fun releaseListener(service: ProxyService) {
        service.onStartCommand(Intent().setAction(ProxyService.ACTION_STOP), 0, 99)
        assertTrue("listener did not release the port in time", await {
            service.serviceState.value.logs.any {
                it.text.contains(service.getString(R.string.proxy_stopped_log))
            }
        })
    }

    private fun await(condition: () -> Boolean): Boolean {
        // Wall-clock polling is inherently racy on loaded CI runners, so the deadline is
        // generous; nanoTime keeps a system clock jump from stretching or cancelling the wait.
        val deadline = System.nanoTime() + 10_000_000_000L // 10 s
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }
}
