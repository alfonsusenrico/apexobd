package com.apexobd.core

import com.apexobd.core.elm.Elm327Client
import com.apexobd.core.protocol.ObdPidRegistry
import com.apexobd.core.transport.ObdTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class PidDiscoveryReliabilityTest {

    private class ScriptedTransport(
        private val responder: (String) -> String
    ) : ObdTransport {
        private val _connected = MutableStateFlow(true)
        override val isConnected: StateFlow<Boolean> = _connected
        override val endpointLabel: String = "scripted"
        val commands = mutableListOf<String>()

        override suspend fun connect() { _connected.value = true }
        override suspend fun disconnect() { _connected.value = false }
        override suspend fun sendCommand(command: String, timeoutMs: Long): String {
            commands += command
            return responder(command)
        }
    }

    @Test
    fun `discoverSupportedPids retries transient 0140 NO DATA and unions multi-ECU bitmasks`() = runTest {
        var block40Calls = 0
        val transport = ScriptedTransport { cmd ->
            when (cmd) {
                // 0100: advertises 0C, 0D, and 20
                "0100" -> "410000180001"
                // 0120: advertises 31 and 40
                "0120" -> "412000008001"
                // 0140: fails first attempt with NO DATA, then returns TCM (7E9 empty) + ECM (7E8 with 42, 45, 46)
                "0140" -> {
                    block40Calls++
                    if (block40Calls == 1) {
                        "NO DATA"
                    } else {
                        // Line 1 (TCM): 41 40 00 00 00 00
                        // Line 2 (ECM): byte0 = 0x4C = bits for 42 (0x40), 45 (0x08), 46 (0x04)
                        "414000000000\r41404C000000"
                    }
                }
                else -> "NO DATA"
            }
        }
        val client = Elm327Client(transport)
        val discovered = ObdPidRegistry.discoverSupportedPids(client, isCanProtocol = true)

        assertTrue("0C" in discovered)
        assertTrue("0D" in discovered)
        assertTrue("31" in discovered)
        assertTrue("42" in discovered) // voltage
        assertTrue("45" in discovered) // rel_throttle
        assertTrue("46" in discovered) // ambient_temp
        assertTrue(block40Calls == 2)
    }
}
