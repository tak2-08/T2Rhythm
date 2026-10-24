// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Opt-in LAN invitations. Discovery only permits an approval request, never library access.
 * TXT records are untrusted: validate bounds and expiry, pin TLS, then compare the pairing code.
 * Call lifecycle methods on the main thread. All platform callbacks are marshalled there too. */
class NearbyStreaming(context: Context, private val ownServer: String) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val _devices = MutableStateFlow<List<StreamingProtocol.Invite>>(emptyList())
    val devices = _devices.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching = _searching.asStateFlow()
    private val _advertising = MutableStateFlow(false)
    val advertising = _advertising.asStateFlow()
    private val _failed = MutableStateFlow(false)
    val failed = _failed.asStateFlow()
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var epoch = 0
    private val queue = ArrayDeque<NsdServiceInfo>()
    private val found = linkedMapOf<String, StreamingProtocol.Invite>()
    private var resolving = false
    private val expireDevices = Runnable { publish() }
    private val stopSearch = Runnable { stopDiscovery() }
    private val stopAdvertise = Runnable { stopAdvertising() }

    fun advertise(invite: StreamingProtocol.Invite) {
        stopAdvertising(); _failed.value = false
        val info = NsdServiceInfo().apply {
            serviceName = "T2Rhythm-${ownServer.take(8)}"
            serviceType = TYPE; port = StreamingProtocol.PORT
            setAttribute("v", "2"); setAttribute("id", invite.server)
            // Keep each TXT value and the complete record small; names may contain UTF-8.
            setAttribute("name", invite.name.take(32)); setAttribute("pin", invite.pin)
            setAttribute("key", invite.secret); setAttribute("until", invite.expires.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { handler.post { if (registration === this) _advertising.value = true else runCatching { nsd.unregisterService(this) } } }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { handler.post { if (registration === this) { registration = null; _advertising.value = false; _failed.value = true } } }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        registration = listener
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener); _advertising.value = true
            handler.postDelayed(stopAdvertise, minOf(120_000, (invite.expires - System.currentTimeMillis()).coerceAtLeast(0)))
        } catch (_: Exception) { registration = null; _advertising.value = false; _failed.value = true }
    }
    fun stopAdvertising() {
        handler.removeCallbacks(stopAdvertise)
        registration?.let { runCatching { nsd.unregisterService(it) } }; registration = null
        _advertising.value = false
    }
    fun discover() {
        stopDiscovery(); _failed.value = false; found.clear(); publish()
        val attempt = ++epoch
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { handler.post { if (epoch == attempt) { _failed.value = true; stopDiscovery() } } }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { handler.post {
                if (epoch == attempt && info.serviceType.trimEnd('.') == TYPE.trimEnd('.') && queue.size < 32 && found.size < 32) {
                    queue.addLast(info); resolve(attempt)
                }
            } }
            override fun onServiceLost(info: NsdServiceInfo) { handler.post {
                if (epoch == attempt) { queue.removeAll { it.serviceName == info.serviceName }; found.remove(info.serviceName); publish() }
            } }
        }
        discovery = listener; _searching.value = true
        try { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener); handler.postDelayed(stopSearch, 30_000) }
        catch (_: Exception) { _failed.value = true; stopDiscovery() }
    }
    @Suppress("DEPRECATION")
    private fun resolve(attempt: Int) {
        if (resolving || queue.isEmpty() || epoch != attempt) return
        resolving = true
        val next = queue.removeFirst()
        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) { handler.post { done(null) } }
                override fun onServiceResolved(info: NsdServiceInfo) { handler.post { done(info) } }
                private fun done(info: NsdServiceInfo?) {
                    if (epoch != attempt) return
                    resolving = false
                    info?.let { service -> runCatching { decode(service) }.getOrNull()?.let { invite ->
                        if (invite.server != ownServer) found[service.serviceName] = invite
                    } }
                    publish(); resolve(attempt)
                }
            })
        } catch (_: Exception) { resolving = false; resolve(attempt) }
    }
    private fun decode(info: NsdServiceInfo): StreamingProtocol.Invite {
        fun text(key: String): String = requireNotNull(info.attributes[key]).also { require(it.size <= 160) }.toString(Charsets.UTF_8)
        require(text("v") == "2")
        val host = requireNotNull(info.host).hostAddress ?: error("No address")
        require('%' !in host) // Scoped link-local addresses are not accepted by the streaming protocol.
        val endpoint = "https://${if (':' in host) "[$host]" else host}:${info.port}"
        val invite = StreamingProtocol.Invite(text("id"), text("name"), endpoint, text("pin"), text("key"), text("until").toLong(), binding = 2)
        return StreamingProtocol.Invite.decode(invite.encode())
    }
    private fun publish() {
        val now = System.currentTimeMillis()
        found.entries.removeAll { it.value.expires <= now }
        _devices.value = found.values.distinctBy { it.server }.toList()
        handler.removeCallbacks(expireDevices)
        found.values.minOfOrNull { it.expires }?.let { handler.postDelayed(expireDevices, (it - now).coerceAtLeast(1)) }
    }
    fun stopDiscovery(clear: Boolean = false) {
        handler.removeCallbacks(stopSearch); epoch++; queue.clear(); resolving = false
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }; discovery = null; _searching.value = false
        if (clear) { found.clear(); publish() }
    }
    fun close() { stopDiscovery(true); stopAdvertising() }
    companion object { private const val TYPE = "_t2rhythm._tcp." }
}
