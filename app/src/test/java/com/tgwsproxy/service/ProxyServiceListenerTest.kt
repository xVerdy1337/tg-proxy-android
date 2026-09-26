package com.tgwsproxy.service

import android.content.Intent
import com.tgwsproxy.R
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProxyServiceListenerTest {

    @Test
    fun listenerThatDiesUnderARunningProxyIsRebound() {
        assumeDefaultPortFree()
        val intent = Intent().setAction(ProxyService.ACTION_START)
        val controller = Robolectric.buildService(ProxyService::class.java, intent).create()
        val service = controller.get()
        try {
            controller.startCommand(0, 1)
            assertTrue("proxy did not bind", await { canConnect() && currentListener(service) != null })

            // Kill the listening socket without going through stop(): what an OS-level failure
            // of the accept socket looks like from inside the server.
            val dead = currentListener(service)!!
            dead.close()

            assertTrue("a dead listener must be rebound while the proxy is meant to run", await {
                val now = currentListener(service)
                now != null && now !== dead && !now.isClosed && canConnect()
            })
            assertTrue(service.serviceState.value.isRunning)
            assertNull(service.serviceState.value.error)
        } finally {
            service.onStartCommand(Intent().setAction(ProxyService.ACTION_STOP), 0, 99)
            await {
                service.serviceState.value.logs.any {
                    it.text.contains(service.getString(R.string.proxy_stopped_log))
                } && !canConnect()
            }
        }
    }

    private fun currentListener(service: ProxyService): ServerSocket? {
        val server = ProxyService::class.java.getDeclaredField("proxyServer")
            .apply { isAccessible = true }.get(service) ?: return null
        return server.javaClass.getDeclaredField("serverSocket")
            .apply { isAccessible = true }.get(server) as ServerSocket?
    }

    private fun canConnect(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ProxyService.DEFAULT_HOST, ProxyService.DEFAULT_PORT), 500) }
        true
    } catch (_: Exception) {
        false
    }

    private fun assumeDefaultPortFree() {
        try {
            ServerSocket(ProxyService.DEFAULT_PORT).close()
        } catch (e: BindException) {
            assumeNoException("port ${ProxyService.DEFAULT_PORT} is already held on this machine; skipping", e)
        }
    }

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }
}
