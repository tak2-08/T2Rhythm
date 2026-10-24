// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

/** Reserved hostnames are internal routes, never DNS names or public HTTPS servers. */
object BluetoothStreamAddress {
    private val host = Regex("bt-([0-9a-f]{12})\\.t2rhythm\\.invalid")
    private val mac = Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
    fun address(hostname: String): String? = host.matchEntire(hostname)?.groupValues?.get(1)
        ?.chunked(2)?.joinToString(":")?.uppercase(java.util.Locale.ROOT)
    fun endpoint(address: String): String {
        require(mac.matches(address))
        return "https://bt-${address.replace(":", "").lowercase(java.util.Locale.ROOT)}.t2rhythm.invalid:${StreamingProtocol.PORT}"
    }
}
