// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import java.io.Closeable
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class StreamMultiplexerTest {
    private class Fixture : Closeable {
        val workers = Executors.newCachedThreadPool { r -> Thread(r, "mux-test").apply { isDaemon = true } }
        private val sockets = ConcurrentLinkedQueue<Socket>()
        private val echo = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val floodStarted = CountDownLatch(1)
        private fun pair(): Pair<Socket, Socket> = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use {
            val first = Socket(InetAddress.getLoopbackAddress(), it.localPort)
            first to it.accept()
        }.also { sockets.add(it.first); sockets.add(it.second) }
        private val transport = pair()
        val client = StreamMultiplexer(transport.first.getInputStream(), transport.first.getOutputStream(), transport.first, workers)
        private val server = StreamMultiplexer(transport.second.getInputStream(), transport.second.getOutputStream(), transport.second, workers,
            accept = { Socket(InetAddress.getLoopbackAddress(), echo.localPort).also(sockets::add) })
        init {
            client.start(); server.start()
            workers.execute {
                try {
                    while (!echo.isClosed) {
                        val socket = echo.accept(); sockets.add(socket)
                        workers.execute {
                            socket.use {
                                try {
                                    val input = it.getInputStream(); val output = it.getOutputStream()
                                    if (input.read() == 0) {
                                        floodStarted.countDown()
                                        repeat(1024) { output.write(ByteArray(16 * 1024) { 42 }) }
                                    } else input.copyTo(output, 4096)
                                    it.shutdownOutput()
                                } catch (_: Exception) { }
                            }
                        }
                    }
                } catch (_: Exception) { }
            }
        }
        fun open(): Socket {
            val (caller, bridge) = pair()
            caller.soTimeout = 15_000; caller.receiveBufferSize = 1024; bridge.sendBufferSize = 1024
            client.attach(bridge)
            return caller
        }
        fun echo(bytes: ByteArray): ByteArray = open().use { socket ->
            val write = workers.submit {
                socket.getOutputStream().write(1)
                // Real streams cross many frame and TCP packet boundaries.
                bytes.asList().chunked(701).forEach { chunk -> socket.getOutputStream().write(chunk.toByteArray()) }
                socket.shutdownOutput()
            }
            socket.getInputStream().readBytes().also { write.get(20, TimeUnit.SECONDS) }
        }
        override fun close() {
            client.close(); server.close(); echo.close(); sockets.forEach { runCatching { it.close() } }; workers.shutdownNow()
        }
    }
    @Test(timeout = 60_000) fun concurrentStreamsPreserveBytesAndDrainTheirFinalFrames() {
        Fixture().use { f ->
            val jobs = (1..4).map { seed -> f.workers.submit<Boolean> {
                val bytes = ByteArray(256 * 1024 + seed) { ((it * seed) % 251).toByte() }
                bytes.contentEquals(f.echo(bytes))
            } }
            jobs.forEach { assertTrue(it.get(45, TimeUnit.SECONDS)) }
        }
    }
    @Test(timeout = 30_000) fun pausedAudioAndCancelledRequestsDoNotBlockControls() {
        Fixture().use { f ->
            val audio = f.open(); audio.getOutputStream().write(0)
            assertTrue(f.floodStarted.await(10, TimeUnit.SECONDS))
            repeat(4) {
                f.open().use { cancelled ->
                    cancelled.getOutputStream().write(1)
                    cancelled.getOutputStream().write(ByteArray(16 * 1024) { 37 })
                }
            } // Cancel while bytes and credit acknowledgements are still in flight.
            val control = "library refresh while audio is buffered".toByteArray()
            assertArrayEquals(control, f.echo(control))
            audio.close()
            assertArrayEquals(control, f.echo(control))
        }
    }
    @Test(timeout = 10_000) fun malformedLargeFramesCloseTheLinkBeforeAllocatingThePayload() {
        val workers = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            Socket(InetAddress.getLoopbackAddress(), listener.localPort).use { peer ->
                listener.accept().use { wire ->
                    val mux = StreamMultiplexer(wire.getInputStream(), wire.getOutputStream(), wire, workers)
                    try {
                        mux.start()
                        DataOutputStream(peer.getOutputStream()).apply { writeByte(2); writeInt(1); writeInt(Int.MAX_VALUE); flush() }
                        peer.soTimeout = 5000
                        assertEquals(-1, peer.getInputStream().read())
                        assertTrue(mux.isClosed)
                    } finally { mux.close(); workers.shutdownNow() }
                }
            }
        }
    }
}
