// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.app.features.streaming

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One RFCOMM link, bounded independent TLS byte streams. Credits prevent a buffered/paused
 * player from blocking library controls or creating an unbounded audio queue. No HTTP is parsed. */
internal class StreamMultiplexer(
    input: InputStream, output: OutputStream, private val transport: Closeable,
    private val executor: Executor, private val accept: (() -> Socket)? = null,
) : Closeable {
    private val source = DataInputStream(input)
    private val sink = DataOutputStream(output)
    private val channels = ConcurrentHashMap<Int, Channel>()
    private val nextId = AtomicInteger(1)
    private val ended = AtomicBoolean(false)
    val isClosed get() = ended.get()
    private val outputLock = Any()
    private val registrationLock = Any()
    fun start() { executor.execute { receive() } }
    fun run() { receive() }
    @Synchronized fun attach(socket: Socket) {
        val channel = synchronized(registrationLock) {
            check(accept == null && !isClosed && channels.size < MAX_CHANNELS)
            val id = nextId.getAndIncrement(); check(id > 0)
            Channel(id, socket).also { channels[id] = it }
        }
        val id = channel.id
        try { send(OPEN, id); channel.start() }
        catch (e: Exception) { drop(channel, false); throw e }
    }
    private fun receive() {
        try {
            while (!isClosed) {
                val kind = source.readUnsignedByte(); val id = source.readInt(); val length = source.readInt()
                if (id <= 0 || length !in 0..FRAME || (kind != DATA && length != 0)) throw IOException("Invalid Bluetooth frame")
                val bytes = ByteArray(length); source.readFully(bytes)
                when (kind) {
                    OPEN -> {
                        if (accept == null || channels.containsKey(id)) throw IOException("Invalid channel")
                        if (channels.size >= MAX_CHANNELS) { send(CLOSE, id); continue }
                        val socket = try { accept.invoke() } catch (_: Exception) { send(CLOSE, id); continue }
                        val channel = Channel(id, socket)
                        val registered = synchronized(registrationLock) {
                            if (isClosed) false else { channels[id] = channel; true }
                        }
                        if (!registered) { socket.close(); break }
                        channel.start()
                    }
                    DATA -> channels[id]?.let {
                        if (length == 0 || !it.incoming.offer(bytes)) throw IOException("Bluetooth receive window exceeded")
                    }
                    CREDIT -> channels[id]?.credit()
                    EOF -> channels[id]?.let { if (!it.incoming.offer(ByteArray(0))) throw IOException("Invalid end of stream") }
                    CLOSE -> channels[id]?.let { drop(it, false) }
                    else -> throw IOException("Unsupported Bluetooth frame")
                }
            }
        } catch (_: Exception) { }
        finally { close() }
    }
    private fun send(kind: Int, id: Int, bytes: ByteArray = ByteArray(0), length: Int = bytes.size) = synchronized(outputLock) {
        if (isClosed) throw IOException("Bluetooth link closed")
        try { sink.writeByte(kind); sink.writeInt(id); sink.writeInt(length); if (length > 0) sink.write(bytes, 0, length); sink.flush() }
        catch (e: Exception) { close(); throw e }
    }
    private inner class Channel(val id: Int, val socket: Socket) {
        val incoming = ArrayBlockingQueue<ByteArray>(WINDOW + 1)
        val credits = Semaphore(WINDOW)
        val closed = AtomicBoolean(false)
        private val halves = AtomicInteger(0)
        fun start() {
            executor.execute {
                try {
                    val input = socket.getInputStream(); val bytes = ByteArray(FRAME)
                    while (!closed.get()) {
                        credits.acquire()
                        if (closed.get()) break
                        val n = input.read(bytes)
                        if (n < 0) { send(EOF, id); halfEnded(); break }
                        if (n > 0) send(DATA, id, bytes, n) else credits.release()
                    }
                } catch (_: Exception) { drop(this, true) }
            }
            executor.execute {
                try {
                    val output = socket.getOutputStream()
                    while (!closed.get()) {
                        val bytes = incoming.take()
                        if (closed.get()) break
                        if (bytes.isEmpty()) { socket.shutdownOutput(); halfEnded(); break }
                        output.write(bytes); output.flush(); send(CREDIT, id)
                    }
                } catch (_: Exception) { drop(this, true) }
            }
        }
        private fun halfEnded() { if (halves.incrementAndGet() == 2) drop(this, false) }
        @Synchronized fun credit() {
            if (closed.get()) return
            if (credits.availablePermits() >= WINDOW) throw IOException("Invalid Bluetooth credit")
            credits.release()
        }
        @Synchronized fun close() {
            if (closed.compareAndSet(false, true)) {
                runCatching { socket.close() }; incoming.clear(); incoming.offer(ByteArray(0)); credits.release(WINDOW)
            }
        }
    }
    private fun drop(channel: Channel, notify: Boolean) {
        if (channels.remove(channel.id, channel)) {
            channel.close()
            if (notify && !isClosed) runCatching { send(CLOSE, channel.id) }
        }
    }
    override fun close() {
        val active = synchronized(registrationLock) {
            if (!ended.compareAndSet(false, true)) return
            channels.values.toList()
        }
        runCatching { transport.close() }
        active.forEach { drop(it, false) }
    }
    companion object {
        private const val FRAME = 16 * 1024
        private const val WINDOW = 4
        private const val MAX_CHANNELS = 8
        private const val OPEN = 1
        private const val DATA = 2
        private const val CREDIT = 3
        private const val EOF = 4
        private const val CLOSE = 5
    }
}
