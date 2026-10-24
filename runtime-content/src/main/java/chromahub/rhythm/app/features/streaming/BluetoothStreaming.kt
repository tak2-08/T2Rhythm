// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** RFCOMM carries the existing pinned TLS protocol unchanged, including byte ranges and revocation.
 * Android secure RFCOMM may ask for system pairing. No discoverability or pairing is enabled silently. */
@SuppressLint("MissingPermission")
object BluetoothStreaming {
    private val service = UUID.fromString("39209bd6-7d4f-478c-a24c-f2405c8ee625")
    private var appContext: Context? = null
    private val io = Executors.newCachedThreadPool { task -> Thread(task, "T2-Bluetooth").apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "T2-Bluetooth-timeout").apply { isDaemon = true } }
    private val slots = Semaphore(12)
    private val hostSockets = ConcurrentHashMap.newKeySet<Closeable>()
    private val clientLock = Any()
    private val clientLinks = ConcurrentHashMap<String, StreamMultiplexer>()
    private val bridges = linkedMapOf<String, ServerSocket>()
    @Volatile private var listener: BluetoothServerSocket? = null
    @Volatile private var invitation: StreamingProtocol.Invite? = null
    fun initialize(context: Context) { appContext = context.applicationContext }
    fun adapter(): BluetoothAdapter? = appContext?.getSystemService(BluetoothManager::class.java)?.adapter
    fun isBluetooth(host: String) = BluetoothStreamAddress.address(host) != null
    private fun address(host: String): String = requireNotNull(BluetoothStreamAddress.address(host))
    fun endpoint(address: String): String = BluetoothStreamAddress.endpoint(address)
    /** Keep serving already approved peers after the two minute invitation window closes. */
    @Synchronized fun host(invite: StreamingProtocol.Invite) {
        invitation = invite.copy(expires = minOf(invite.expires, System.currentTimeMillis() + 120_000))
        if (listener != null) return
        val server = adapter()?.takeIf { it.isEnabled }?.listenUsingRfcommWithServiceRecord("T2Rhythm", service)
            ?: throw IOException("Bluetooth is unavailable")
        listener = server
        io.execute {
            try {
                while (listener === server) {
                    val socket = server.accept()
                    val accepted = synchronized(this) {
                        if (listener !== server || !slots.tryAcquire()) false else { hostSockets.add(socket); true }
                    }
                    if (!accepted) { socket.close(); continue }
                    io.execute {
                        try {
                            val deadline = timer.schedule({ runCatching { socket.close() } }, 10, TimeUnit.SECONDS)
                            val mode = try { socket.inputStream.read() } finally { deadline.cancel(false) }
                            if (mode == 1) {
                                val current = invitation?.takeIf { it.expires > System.currentTimeMillis() }
                                DataOutputStream(socket.outputStream).writeUTF(current?.encode().orEmpty())
                            } else if (mode == 2) {
                                val mux = StreamMultiplexer(socket.inputStream, socket.outputStream, socket, io, accept = {
                                    Socket().apply { connect(InetSocketAddress(InetAddress.getLoopbackAddress(), StreamingProtocol.PORT), 3000) }
                                })
                                hostSockets.add(mux)
                                try { mux.run() } finally { hostSockets.remove(mux); mux.close() }
                            }
                        } catch (_: Exception) { }
                        finally { hostSockets.remove(socket); runCatching { socket.close() }; slots.release() }
                    }
                }
            } catch (_: Exception) { }
            finally { synchronized(this) { if (listener === server) listener = null }; runCatching { server.close() } }
        }
    }
    @Synchronized fun stopHosting() {
        invitation = null; val old = listener; listener = null; runCatching { old?.close() }
        hostSockets.toList().forEach { runCatching { it.close() } }; hostSockets.clear()
    }
    fun hideInvitation() { invitation = null }
    suspend fun invite(address: String): StreamingProtocol.Invite = withContext(Dispatchers.IO) {
        // Reuse is not possible for the one-shot invitation exchange. Close a previous data
        // link explicitly before opening the same RFCOMM channel again.
        forget(endpoint(address))
        val socket = connect(address)
        val timeout = timer.schedule({ runCatching { socket.close() } }, 15, TimeUnit.SECONDS)
        try {
            socket.outputStream.write(1)
            val text = DataInputStream(socket.inputStream).readUTF()
            require(text.length <= 8192)
            StreamingProtocol.Invite.decode(text).also { require(it.binding == 2) }.copy(endpoint = endpoint(address))
        } finally { timeout.cancel(false); socket.close() }
    }
    private fun connect(address: String): BluetoothSocket {
        val bluetooth = adapter()?.takeIf { it.isEnabled } ?: throw IOException("Bluetooth is unavailable")
        val socket = bluetooth.getRemoteDevice(address).createRfcommSocketToServiceRecord(service)
        val timeout = timer.schedule({ runCatching { socket.close() } }, 10, TimeUnit.SECONDS)
        try { bluetooth.cancelDiscovery(); socket.connect(); return socket }
        catch (e: Exception) { socket.close(); throw e }
        finally { timeout.cancel(false) }
    }
    /** OkHttp still owns a normal TCP socket, so cancellation and read deadlines close RFCOMM too. */
    fun socketFactory(host: String): SocketFactory {
        require(isBluetooth(host))
        return object : SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                override fun connect(endpoint: SocketAddress?, timeout: Int) {
                    super.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), bridge(host).localPort), timeout)
                }
            }
            override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()
            override fun createSocket(host: String?, port: Int, local: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()
            override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException()
            override fun createSocket(host: InetAddress?, port: Int, local: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()
        }
    }
    @Synchronized private fun bridge(host: String): ServerSocket = bridges.getOrPut(host) {
        if (bridges.size >= StreamingProtocol.MAX_PEERS) throw IOException("Too many Bluetooth routes")
        ServerSocket(0, 8, InetAddress.getLoopbackAddress()).also { server ->
            io.execute {
                try {
                    while (!server.isClosed) {
                        val tcp = server.accept()
                        io.execute {
                            try { link(host).attach(tcp) }
                            catch (_: Exception) { runCatching { tcp.close() } }
                        }
                    }
                } catch (_: Exception) { }
            }
        }
    }
    @Synchronized fun forget(endpoint: String) {
        val host = runCatching { java.net.URI(endpoint).host }.getOrNull() ?: return
        bridges.remove(host)?.let { runCatching { it.close() } }
        clientLinks.remove(host)?.close()
    }
    private fun link(host: String): StreamMultiplexer = synchronized(clientLock) {
        clientLinks[host]?.takeIf { !it.isClosed } ?: run {
            val socket = connect(address(host))
            try {
                socket.outputStream.write(2)
                StreamMultiplexer(socket.inputStream, socket.outputStream, socket, io).also { clientLinks[host] = it; it.start() }
            } catch (e: Exception) { socket.close(); throw e }
        }
    }
}
