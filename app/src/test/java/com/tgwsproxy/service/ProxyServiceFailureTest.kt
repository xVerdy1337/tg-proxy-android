package com.tgwsproxy.service

import android.content.Intent
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
                !ProxyService.serviceState.value.isRunning
            })
            assertTrue("failed startup must stop the service", shadowOf(service).isStoppedBySelf)
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
