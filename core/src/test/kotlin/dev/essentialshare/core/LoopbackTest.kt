package dev.essentialshare.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemStore : Store {
    private val m = HashMap<String, String>()
    override fun get(key: String) = m[key]
    override fun put(key: String, value: String) { m[key] = value }
}

class DirReceiver(val dir: File) : FileReceiver {
    override fun open(name: String, size: Long, mime: String): SinkHandle {
        val target = File(dir, name).also { it.parentFile.mkdirs() }
        val part = File(target.path + ".part")
        val os = part.outputStream().buffered(1 shl 16)
        return object : SinkHandle {
            override val out: OutputStream = os
            override fun finish(): String { os.close(); part.renameTo(target); return target.path }
            override fun abort() { os.close(); part.delete() }
        }
    }
}

class LoopbackTest {
    private fun node(name: String, dir: File): ShareNode {
        val store = MemStore()
        return ShareNode(Identity.load(store), store, NodeConfig(name), DirReceiver(dir), Platform.WINDOWS) { println("[$name] $it") }
    }

    private fun sha(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return Crypto.hex(md.digest())
    }

    private fun outgoing(f: File) = object : OutgoingFile {
        override val name = f.name
        override val size = f.length()
        override val mime = "application/octet-stream"
        override fun open(): InputStream = f.inputStream()
    }

    @Test
    fun connectNearbyChecksTheTouchedDevice(): Unit = runBlocking<Unit> {
        val a = node("A", Files.createTempDirectory("esA").toFile())
        val b = node("B", Files.createTempDirectory("esB").toFile())
        a.start(); b.start()
        try {
            // an id prefix that does not belong to B is refused, B stays unlinked
            val wrong = java.util.concurrent.CompletableFuture<Boolean>()
            a.connectNearby("ffffffffffff".takeIf { !b.selfId.startsWith(it) } ?: "000000000000", "127.0.0.1", b.port) { wrong.complete(it) }
            assertEquals(false, wrong.get(10, java.util.concurrent.TimeUnit.SECONDS))

            // the right prefix links the two, and a second call (the other phone touching at once) is still a success
            val prefix = b.selfId.take(12)
            val first = java.util.concurrent.CompletableFuture<Boolean>()
            a.connectNearby(prefix, "127.0.0.1", b.port) { first.complete(it) }
            assertEquals(true, first.get(10, java.util.concurrent.TimeUnit.SECONDS))
            val again = java.util.concurrent.CompletableFuture<Boolean>()
            a.connectNearby(prefix, "127.0.0.1", b.port) { again.complete(it) }
            assertEquals(true, again.get(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(withTimeout(10000) { b.pairing.first { it.isNotEmpty() } }.first().peerId == a.selfId)

            // the touched phone is told, even before it has agreed to pair, so it can show its side of the animation
            val touched = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(10000) { b.events.first { it is NodeEvent.Touched } }
            }
            a.announceTouch(prefix)
            assertEquals(a.selfId, (touched.await() as NodeEvent.Touched).peerId)

            // nothing listens on the address and nothing was seen: a clean failure, not a hang
            val dead = java.util.concurrent.CompletableFuture<Boolean>()
            a.connectNearby("123456789abc".takeIf { !b.selfId.startsWith(it) } ?: "cba987654321", "127.0.0.1", 1) { dead.complete(it) }
            assertEquals(false, dead.get(10, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            a.stop(); b.stop()
        }
    }

    @Test
    fun pairAndTransfer(): Unit = runBlocking<Unit> {
        val dirA = Files.createTempDirectory("esA").toFile()
        val dirB = Files.createTempDirectory("esB").toFile()
        val a = node("A", dirA); val b = node("B", dirB)
        a.start(); b.start()
        try {
            a.connectManually("127.0.0.1", b.port)
            val reqB = withTimeout(10000) { b.pairing.first { it.isNotEmpty() } }.first()
            val reqA = withTimeout(10000) { a.pairing.first { it.isNotEmpty() } }.first()
            assertEquals(reqA.code, reqB.code, "both sides must show the same code")
            println("pairing code ${reqA.code}")
            a.answerPairing(b.selfId, true)
            b.answerPairing(a.selfId, true)
            withTimeout(10000) { a.peers.first { p -> p.any { it.id == b.selfId && it.state == LinkState.READY } } }

            val big = File(dirA, "big.bin")
            val rnd = java.util.Random(1)
            big.outputStream().buffered().use { o -> val buf = ByteArray(1 shl 20); repeat(300) { rnd.nextBytes(buf); o.write(buf) } }
            val small = File(dirA, "dir/small.txt").also { it.parentFile.mkdirs(); it.writeText("hello") }
            val empty = File(dirA, "empty.txt").also { it.writeText("") }
            val t0 = System.nanoTime()
            a.sendFiles(b.selfId, listOf(outgoing(big), outgoing(small), outgoing(empty)))
            withTimeout(60000) { a.transfers.first { l -> l.size == 3 && l.all { it.state == TransferState.DONE } } }
            val secs = (System.nanoTime() - t0) / 1e9
            println("300 MB in %.2fs = %.0f MB/s".format(secs, 300 / secs))
            assertEquals(sha(big), sha(File(dirB, "big.bin")))
            assertEquals("hello", File(dirB, "small.txt").readText())
            assertTrue(File(dirB, "empty.txt").exists())

            // text goes the other way over the same link
            b.sendText(a.selfId, "привет")
            val item = withTimeout(5000) { a.texts.first { it.isNotEmpty() } }.first()
            assertEquals("привет", item.text)

            // reconnect: trusted on both sides, no pairing needed
            a.disconnectAll()
            Thread.sleep(300)
            a.connectManually("127.0.0.1", b.port)
            withTimeout(10000) { a.peers.first { p -> p.any { it.id == b.selfId && it.state == LinkState.READY } } }
        } finally {
            a.stop(); b.stop()
        }
    }
}
