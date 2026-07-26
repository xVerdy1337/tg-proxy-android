package com.tgwsproxy.service

import android.content.Intent
import com.tgwsproxy.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.ServerSocket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProxyServiceFailureTest {

    @Test
    fun failedStartStopsTheForegroundService() {
        ServerSocket(ProxyService.DEFAULT_PORT).use {
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
    fun startThatSucceedsRetiresThePreviousFailure() {
        val intent = Intent().setAction(ProxyService.ACTION_START)
        val controller = Robolectric.buildService(ProxyService::class.java, intent).create()
        val service = controller.get()

        try {
            ServerSocket(ProxyService.DEFAULT_PORT).use {
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
     * Hand ACTION_STOP straight to the service — the controller was built with the start intent and
     * this needs the listener closed, not another lifecycle step. Without it the accept loop keeps
     * port 1443 for the rest of the JVM and every later bind in this suite fails.
     */
    private fun releaseListener(service: ProxyService) {
        service.onStartCommand(Intent().setAction(ProxyService.ACTION_STOP), 0, 99)
        await {
            service.serviceState.value.logs.any {
                it.text.contains(service.getString(R.string.proxy_stopped_log))
            }
        }
    }

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }
}
