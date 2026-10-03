package dev.essentialshare.core

import java.io.File
import java.io.InputStream
import java.nio.file.Files

/**
 * A headless stand-in for the phone, used to try the real PC app on the real network:
 * it advertises itself, accepts the pairing from its side, then sends a file and a text to the PC.
 * Run with: gradlew :core:fakePhone
 */
fun main() {
    val dir = Files.createTempDirectory("fakephone").toFile()
    val store = object : Store {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String) { m[key] = value }
    }
    val node = ShareNode(Identity.load(store), store, NodeConfig("Fake Phone"), DirReceiver(dir), Platform.ANDROID) { println("[log] $it") }
    node.start()
    println("fake phone up, tcp port ${node.port}, id ${node.selfId}, saving into $dir")

    val payload = File(dir, "from-phone.bin").also { f -> f.outputStream().use { o -> val b = ByteArray(1 shl 20); java.util.Random(7).let { r -> repeat(120) { r.nextBytes(b); o.write(b) } } } }
    var sent = false
    Thread {
        var last = ""
        while (true) {
            Thread.sleep(500)
            val peers = node.peers.value.joinToString { "${it.name}:${it.state}${if (it.trusted) "+trusted" else ""}" }
            if (peers != last) { println("peers: $peers"); last = peers }
            node.pairing.value.firstOrNull()?.let { r ->
                println("PAIRING with ${r.peerName}: code ${r.code} -> accepting on the phone side")
                node.answerPairing(r.peerId, true)
            }
            val pc = node.peers.value.firstOrNull { it.state == LinkState.READY }
            if (pc != null && !sent) {
                sent = true
                println("PC is ready -> sending 120 MB file and a text")
                node.sendText(pc.id, "hello from the fake phone")
                node.sendFiles(pc.id, listOf(object : OutgoingFile {
                    override val name = "from-phone.bin"
                    override val size = payload.length()
                    override val mime = "application/octet-stream"
                    override fun open(): InputStream = payload.inputStream()
                }))
            }
        }
    }.apply { isDaemon = true; start() }
    Thread {
        val lastLine = HashMap<String, String>()
        var textShown = 0
        while (true) {
            Thread.sleep(700)
            node.transfers.value.forEach { t ->
                val line = "${t.state} ${(t.fraction * 100 / 10).toInt() * 10}% ${t.error ?: ""}"
                if (lastLine.put("${t.direction}${t.id}", line) != line) println("transfer ${t.direction} ${t.name}: $line ${t.speed / 1_000_000} MB/s")
            }
            val incoming = node.texts.value.filter { it.incoming }
            if (incoming.size > textShown) { textShown = incoming.size; println("text from PC: ${incoming.first().text}") }
        }
    }.apply { isDaemon = true; start() }
    Thread.sleep(300_000)
}
