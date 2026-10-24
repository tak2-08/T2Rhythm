// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import java.net.URI
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class BluetoothStreamAddressTest {
    @Test fun bluetoothRouteRoundTripsThroughPersistedHttpsEndpoints() {
        val endpoint = BluetoothStreamAddress.endpoint("AA:bb:01:23:45:67")
        assertEquals(endpoint, StreamingProtocol.endpoint(endpoint))
        assertEquals("AA:BB:01:23:45:67", BluetoothStreamAddress.address(URI(endpoint).host))
    }
    @Test fun onlyExactReservedHostnamesCanRouteToBluetooth() {
        listOf("localhost", "bt-aabb01234567.t2rhythm.invalid.evil.com", "bt-aabb01234567.t2rhythm.invalid:8898",
            "bt-aabb0123456.t2rhythm.invalid", "bt-aabb012345gg.t2rhythm.invalid", "private.t2rhythm.invalid").forEach {
            assertNull(BluetoothStreamAddress.address(it))
        }
    }
    @Test fun malformedMacAddressesCannotBecomeEndpoints() {
        listOf("", "AA:BB:CC:DD:EE", "AA:BB:CC:DD:EE:FF/", "AA:BB:CC:DD:EE:FF@evil.com").forEach {
            assertTrue(runCatching { BluetoothStreamAddress.endpoint(it) }.isFailure)
        }
    }
    @Test fun localeDoesNotChangeSavedRoutes() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("https://bt-aabbccddeeff.t2rhythm.invalid:8898", BluetoothStreamAddress.endpoint("AA:BB:CC:DD:EE:FF"))
        } finally { Locale.setDefault(before) }
    }
}
