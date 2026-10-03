package dev.essentialshare.core

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

class Beacon(val id: String, val port: Int, val platform: Platform, val name: String)

/**
 * LAN discovery: a tiny UDP beacon every couple of seconds, sent as directed broadcast and as multicast on
 * every interface. Receiving is a single socket bound to the discovery port.
 */
class Discovery(
    private val selfId: String,
    private val tcpPort: Int,
    private val platform: Platform,
    private val config: NodeConfig,
    private val onBeacon: (Beacon, InetAddress) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    private val wake = Object()
    private var recv: MulticastSocket? = null
    private var send: MulticastSocket? = null
    private var rxThread: Thread? = null
    private var txThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        rxThread = Thread({ receiveLoop() }, "es-discovery-rx").apply { isDaemon = true; start() }
        txThread = Thread({ sendLoop() }, "es-discovery-tx").apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        runCatching { recv?.close() }
        runCatching { send?.close() }
        synchronized(wake) { wake.notifyAll() }
    }

    /** Announce right now (used when a new device shows up so it finds us quickly too). */
    fun announceNow() = synchronized(wake) { wake.notifyAll() }

    private fun payload(): ByteArray {
        val name = config.name.toByteArray(Charsets.UTF_8).let { if (it.size > 100) it.copyOf(100) else it }
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(hexToBytes(selfId))
        out.write(tcpPort ushr 8); out.write(tcpPort and 0xff)
        out.write(platform.code)
        out.write(name.size)
        out.write(name)
        return out.toByteArray()
    }

    private fun parse(data: ByteArray, len: Int): Beacon? {
        if (len < 4 + 16 + 4 || !data.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        val id = Crypto.hex(data.copyOfRange(4, 20))
        val port = ((data[20].toInt() and 0xff) shl 8) or (data[21].toInt() and 0xff)
        val plat = Platform.of(data[22].toInt() and 0xff)
        val n = data[23].toInt() and 0xff
        if (24 + n > len) return null
        return Beacon(id, port, plat, String(data, 24, n, Charsets.UTF_8))
    }

    private fun receiveLoop() {
        while (running.get()) {
            try {
                val s = MulticastSocket(null)
                s.reuseAddress = true
                s.bind(InetSocketAddress(PORT))
                recv = s
                joinGroups(s)
                val buf = ByteArray(512)
                var lastJoin = System.currentTimeMillis()
                s.soTimeout = 3000
                while (running.get()) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val b = parse(p.data, p.length) ?: continue
                        if (b.id == selfId) continue
                        onBeacon(b, p.address)
                    } catch (_: java.net.SocketTimeoutException) {
                    }
                    if (System.currentTimeMillis() - lastJoin > 15000) {
                        joinGroups(s); lastJoin = System.currentTimeMillis()
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    log("discovery rx: $e")
                    Thread.sleep(2000)
                }
            } finally {
                runCatching { recv?.close() }
            }
        }
    }

    private fun joinGroups(s: MulticastSocket) {
        val group = InetSocketAddress(InetAddress.getByName(GROUP), 0)
        for (ni in interfaces()) {
            if (!ni.supportsMulticast()) continue
            runCatching { s.joinGroup(group, ni) }
        }
    }

    private fun interfaces(): List<NetworkInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
    }.getOrDefault(emptyList())

    private fun sendLoop() {
        var burst = 3
        while (running.get()) {
            try {
                val s = send ?: MulticastSocket().also { it.broadcast = true; send = it }
                val data = payload()
                val groupAddr = InetAddress.getByName(GROUP)
                for (ni in interfaces()) {
                    for (ia in ni.interfaceAddresses) {
                        val bc = ia.broadcast ?: continue
                        if (ia.address !is Inet4Address) continue
                        runCatching { s.send(DatagramPacket(data, data.size, bc, PORT)) }
                    }
                    if (ni.supportsMulticast()) runCatching {
                        s.networkInterface = ni
                        s.send(DatagramPacket(data, data.size, groupAddr, PORT))
                    }
                }
                runCatching { s.send(DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), PORT)) }
            } catch (e: Exception) {
                if (running.get()) log("discovery tx: $e")
                runCatching { send?.close() }; send = null
            }
            val wait = if (burst > 0) { burst--; 250L } else 2000L
            synchronized(wake) { runCatching { (wake as Object).wait(wait) } }
        }
    }

    companion object {
        const val PORT = 47891
        const val GROUP = "239.255.42.99"
        private val MAGIC = byteArrayOf('E'.code.toByte(), 'S'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
        fun hexToBytes(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
