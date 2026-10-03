package dev.essentialshare.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Trusted (paired) devices, kept in the host-provided [Store]. */
class TrustStore(private val store: Store) {
    private val map = ConcurrentHashMap<String, String>()

    init {
        store.get("trusted")?.lineSequence()?.forEach {
            val i = it.indexOf('\t')
            if (i > 0) map[it.substring(0, i)] = it.substring(i + 1)
        }
    }

    fun isTrusted(id: String) = map.containsKey(id)
    fun name(id: String): String? = map[id]
    fun all(): Map<String, String> = map.toMap()

    fun add(id: String, name: String) {
        map[id] = name.replace('\n', ' ').replace('\t', ' ')
        save()
    }

    fun remove(id: String) {
        map.remove(id)
        save()
    }

    private fun save() = store.put("trusted", map.entries.joinToString("\n") { "${it.key}\t${it.value}" })
}

/**
 * One device on the local network: advertises itself, accepts connections, pairs with other devices and moves
 * files and text over an encrypted TCP link. Everything the UI needs is exposed as flows.
 */
class ShareNode(
    val identity: Identity,
    store: Store,
    val config: NodeConfig,
    private val receiver: FileReceiver,
    private val platform: Platform,
    private val log: (String) -> Unit = {},
) {
    val trust = TrustStore(store)

    private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val peers: StateFlow<List<PeerInfo>> = _peers.asStateFlow()
    private val _transfers = MutableStateFlow<List<TransferInfo>>(emptyList())
    val transfers: StateFlow<List<TransferInfo>> = _transfers.asStateFlow()
    private val _texts = MutableStateFlow<List<TextItem>>(emptyList())
    val texts: StateFlow<List<TextItem>> = _texts.asStateFlow()
    private val _pairing = MutableStateFlow<List<PairingRequest>>(emptyList())
    val pairing: StateFlow<List<PairingRequest>> = _pairing.asStateFlow()
    private val _events = MutableSharedFlow<NodeEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<NodeEvent> = _events.asSharedFlow()

    val selfId get() = identity.id
    @Volatile var port = 0
        private set

    // ---- state -----------------------------------------------------------------------------------

    private class Seen(var name: String, var platform: Platform, var address: InetAddress, var port: Int, var lastSeen: Long)

    private val seen = ConcurrentHashMap<String, Seen>()
    private val links = ConcurrentHashMap<String, Link>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    private val lastAttempt = ConcurrentHashMap<String, Long>()
    private val outs = ConcurrentHashMap<Long, OutXfer>()
    private val ins = ConcurrentHashMap<Long, InXfer>()
    private val dirty = AtomicBoolean(false)
    private val unpairedLinks = AtomicInteger(0)

    private val io = Executors.newCachedThreadPool { r -> Thread(r, "es-io").apply { isDaemon = true } }
    private var ticker: ScheduledExecutorService? = null
    private var server: ServerSocket? = null
    private var discovery: Discovery? = null
    private val running = AtomicBoolean(false)

    // ---- lifecycle -------------------------------------------------------------------------------

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(0), 32)
        server = s
        port = s.localPort
        io.execute { acceptLoop(s) }
        discovery = Discovery(selfId, port, platform, config, ::onBeacon, log).also { it.start() }
        ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "es-tick").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ runCatching { tick() } }, 150, 150, TimeUnit.MILLISECONDS)
        }
        publishPeers()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        val d = discovery; val t = ticker; val sv = server; val ls = links.values.toList()
        discovery = null; ticker = null; server = null
        seen.clear()
        // socket work stays off the caller's thread (Android forbids it on the main thread)
        io.execute {
            d?.stop()
            t?.shutdownNow()
            runCatching { sv?.close() }
            ls.forEach { it.close("stopped") }
            publishPeers()
        }
    }

    val isRunning get() = running.get()

    private var tickCount = 0

    private fun tick() {
        if (dirty.compareAndSet(true, false)) publishTransfers()
        tickCount++
        if (tickCount % 4 == 0) {
            val now = System.currentTimeMillis()
            seen.entries.removeIf { now - it.value.lastSeen > 8000 }
            publishPeers()
        }
        if (tickCount % 20 == 0) {
            links.values.forEach { it.pingIfIdle() }
            reconnectTrusted()
        }
    }

    // ---- discovery -------------------------------------------------------------------------------

    private fun onBeacon(b: Beacon, addr: InetAddress) {
        val now = System.currentTimeMillis()
        val existing = seen[b.id]
        if (existing == null) {
            seen[b.id] = Seen(b.name, b.platform, addr, b.port, now)
            discovery?.announceNow()
            publishPeers()
            if (trust.isTrusted(b.id)) io.execute { reconnectTrusted() }
        } else {
            existing.name = b.name; existing.platform = b.platform; existing.address = addr
            existing.port = b.port; existing.lastSeen = now
        }
    }

    /** The side with the smaller id keeps a standing connection to every trusted device that is around. */
    private fun reconnectTrusted() {
        val now = System.currentTimeMillis()
        for ((id, s) in seen) {
            if (selfId >= id || !trust.isTrusted(id)) continue
            if (links[id]?.alive == true || connecting.contains(id)) continue
            if (now - (lastAttempt[id] ?: 0) < 4000) continue
            lastAttempt[id] = now
            io.execute { runCatching { connect(id) } }
        }
    }

    private fun publishPeers() {
        val ids = HashSet<String>()
        val list = ArrayList<PeerInfo>()
        fun add(id: String, name: String, plat: Platform, addr: String?, port: Int) {
            if (!ids.add(id)) return
            val link = links[id]
            val state = when {
                link != null && link.alive && link.ready -> LinkState.READY
                link != null && link.alive -> LinkState.PAIRING
                connecting.contains(id) -> LinkState.CONNECTING
                seen.containsKey(id) -> LinkState.SEEN
                else -> LinkState.OFFLINE
            }
            list.add(PeerInfo(id, name, plat, addr, port, trust.isTrusted(id), state))
        }
        for ((id, s) in seen) add(id, s.name, s.platform, s.address.hostAddress, s.port)
        for ((id, l) in links) if (l.alive) add(id, l.name, l.platform, l.socket.inetAddress?.hostAddress, l.socket.port)
        for ((id, n) in trust.all()) add(id, n, Platform.OTHER, null, 0)
        list.sortWith(compareBy({ -it.state.ordinal }, { it.name.lowercase() }))
        _peers.value = list
    }

    // ---- connections -----------------------------------------------------------------------------

    private fun acceptLoop(s: ServerSocket) {
        while (running.get()) {
            val sock = try { s.accept() } catch (e: IOException) { if (running.get()) log("accept: $e"); break }
            io.execute {
                if (unpairedLinks.get() >= 8) { runCatching { sock.close() }; return@execute }
                runCatching { establish(sock, initiator = false) }.onFailure { log("inbound failed: $it"); runCatching { sock.close() } }
            }
        }
    }

    /** Open (or reuse) a connection to a device seen on the network. Blocks. */
    private fun connect(peerId: String): Link? {
        links[peerId]?.let { if (it.alive) return it }
        val s = seen[peerId] ?: return null
        if (!connecting.add(peerId)) return links[peerId]
        publishPeers()
        try {
            val sock = Socket()
            sock.connect(InetSocketAddress(s.address, s.port), 4000)
            return establish(sock, initiator = true)
        } catch (e: Exception) {
            log("connect $peerId: $e")
            return null
        } finally {
            connecting.remove(peerId)
            publishPeers()
        }
    }

    /** Connect to a device by address, for networks where broadcast does not get through. */
    fun connectManually(host: String, port: Int, onResult: (Boolean) -> Unit = {}) {
        io.execute {
            val ok = try {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), 4000)
                establish(sock, initiator = true) != null
            } catch (e: Exception) { log("manual connect: $e"); false }
            onResult(ok)
        }
    }

    private fun establish(sock: Socket, initiator: Boolean): Link? {
        sock.tcpNoDelay = true
        sock.sendBufferSize = 1 shl 20
        sock.receiveBufferSize = 1 shl 20
        sock.keepAlive = true
        sock.soTimeout = 8000
        val input = DataInputStream(BufferedInputStream(sock.getInputStream(), 1 shl 16))
        val output = BufferedOutputStream(sock.getOutputStream(), 1 shl 16)
        val session = Handshake.run(input, output, identity, initiator)
        val peerId = Identity.idOf(session.peerStatic)
        sock.soTimeout = 30000
        val link = Link(sock, initiator, SecureChannel(input, output, session.sendKey, session.recvKey), peerId, session.code)
        if (!register(link)) { link.close("duplicate"); return null }
        io.execute { link.run() }
        return link
    }

    /** Two devices connecting to each other at once: the link started by the smaller id wins on both sides. */
    private fun register(link: Link): Boolean {
        synchronized(links) {
            val old = links[link.peerId]
            if (old != null && old.alive) {
                if (!(link.canonical(selfId) && !old.canonical(selfId))) return false
                old.close("replaced")
            }
            links[link.peerId] = link
        }
        return true
    }

    // ---- public actions --------------------------------------------------------------------------

    fun sendFiles(peerId: String, files: List<OutgoingFile>) {
        val name = peerName(peerId)
        val batch = files.map { f ->
            OutXfer(Crypto.random.nextLong(), peerId, name, f).also { outs[it.id] = it }
        }
        markDirty()
        io.execute {
            withLink(peerId, { why -> batch.forEach { it.fail(why) } }) { link -> link.sendBatch(batch) }
        }
    }

    fun sendText(peerId: String, text: String, clipboard: Boolean = false) {
        io.execute {
            withLink(peerId, { log("sendText: $it") }) { link -> link.sendText(text, clipboard) }
        }
    }

    /** Push the clipboard to every connected, trusted device. */
    fun sendClipboardToAll(text: String) {
        io.execute { links.values.filter { it.alive && it.ready }.forEach { it.sendText(text, true) } }
    }

    fun answerOffer(transferId: Long, accept: Boolean) {
        val x = ins[transferId] ?: return
        io.execute { x.link.answerOffer(x, accept) }
    }

    fun cancel(transferId: Long) {
        io.execute {
            outs[transferId]?.let { it.link?.sendCancel(it.id); it.cancel(); return@execute }
            ins[transferId]?.let { it.link.sendCancel(it.id); it.link.abortIncoming(it, "canceled", TransferState.CANCELED) }
        }
    }

    fun answerPairing(peerId: String, accept: Boolean) {
        io.execute { links[peerId]?.answerPairing(accept) }
    }

    fun unpair(peerId: String) {
        trust.remove(peerId)
        publishPeers()
        io.execute { links[peerId]?.close("unpaired"); publishPeers() }
    }

    fun clearFinished() {
        outs.values.removeIf { it.finished }
        ins.values.removeIf { it.finished }
        markDirty()
    }

    fun disconnectAll() { io.execute { links.values.forEach { it.close("manual") } } }

    /** Start talking to a device that was seen on the network (this is what starts pairing for a new one). */
    fun connectPeer(peerId: String) {
        io.execute { runCatching { connect(peerId) } }
    }

    /** Push a changed device name to the network and to every open link. */
    fun announceName() {
        discovery?.announceNow()
        io.execute { links.values.forEach { it.hello() } }
    }

    fun peerName(id: String): String = links[id]?.name ?: seen[id]?.name ?: trust.name(id) ?: "Device"

    private fun withLink(peerId: String, fail: (String) -> Unit, action: (Link) -> Unit) {
        val link = connect(peerId)
        if (link == null) { fail("Device not reachable"); return }
        link.afterReady(action, fail)
    }

    private fun markDirty() = dirty.set(true)

    private fun publishTransfers() {
        val list = ArrayList<TransferInfo>(outs.size + ins.size)
        outs.values.forEach { list.add(it.info()) }
        ins.values.forEach { list.add(it.info()) }
        list.sortByDescending { it.startedAt }
        // keep the history bounded
        if (list.size > 300) {
            val drop = list.drop(300).filter { it.finished }
            drop.forEach { outs.remove(it.id); ins.remove(it.id) }
        }
        _transfers.value = list.take(300)
    }

    // ---- transfer bookkeeping --------------------------------------------------------------------

    private abstract inner class Xfer(val id: Long, val peerId: String, val peerName: String, val name: String, val size: Long, val direction: Direction) {
        @Volatile var transferred = 0L
        @Volatile var state = TransferState.OFFERED
        @Volatile var speed = 0L
        val startedAt = System.currentTimeMillis()
        @Volatile var finishedAt = 0L
        @Volatile var result: String? = null
        @Volatile var error: String? = null
        @Volatile var needsAnswer = false
        private var lastBytes = 0L
        private var lastTime = System.nanoTime()
        val finished get() = state == TransferState.DONE || state == TransferState.FAILED || state == TransferState.CANCELED || state == TransferState.DECLINED

        fun info(): TransferInfo {
            val now = System.nanoTime()
            if (state == TransferState.ACTIVE) {
                val dt = (now - lastTime) / 1e9
                if (dt >= 0.25) {
                    val inst = ((transferred - lastBytes) / dt).toLong()
                    speed = if (speed == 0L) inst else (speed * 0.6 + inst * 0.4).toLong()
                    lastBytes = transferred; lastTime = now
                }
            } else speed = 0
            return TransferInfo(id, direction, peerId, peerName, name, size, transferred, state, speed, startedAt, finishedAt, result, error, needsAnswer)
        }

        fun end(s: TransferState, err: String? = null) {
            if (finished) return
            state = s; error = err; finishedAt = System.currentTimeMillis(); needsAnswer = false
            markDirty()
            if (s == TransferState.FAILED) { publishTransfers(); _events.tryEmit(NodeEvent.TransferFailed(info())) }
        }
    }

    private inner class OutXfer(id: Long, peerId: String, peerName: String, val file: OutgoingFile) :
        Xfer(id, peerId, peerName, file.name, file.size, Direction.OUT) {
        @Volatile var link: Link? = null
        @Volatile var canceled = false
        val answer = CompletableFuture<Boolean>()
        fun fail(why: String) { end(TransferState.FAILED, why); answer.complete(false) }
        fun cancel() { canceled = true; end(TransferState.CANCELED); answer.complete(false) }
    }

    private inner class InXfer(id: Long, val link: Link, name: String, size: Long, val mime: String) :
        Xfer(id, link.peerId, link.name, name, size, Direction.IN) {
        @Volatile var sink: SinkHandle? = null
    }

    // ---- the link --------------------------------------------------------------------------------

    private inner class Link(val socket: Socket, val initiator: Boolean, val ch: SecureChannel, val peerId: String, val code: String) {
        @Volatile var name = trust.name(peerId) ?: "Device"
        @Volatile var platform = Platform.OTHER
        @Volatile var alive = true
        @Volatile var ready = false
        @Volatile private var gotHello = false
        @Volatile private var localOk = false
        @Volatile private var remoteOk = false
        @Volatile private var lastRx = System.currentTimeMillis()
        @Volatile private var counted = true
        private val waiting = CopyOnWriteArrayList<Pair<(Link) -> Unit, (String) -> Unit>>()

        init { unpairedLinks.incrementAndGet() }

        fun canonical(selfId: String): Boolean {
            val i = if (initiator) selfId else peerId
            val r = if (initiator) peerId else selfId
            return i < r
        }

        private fun frame(type: Int, body: DataOutputStream.() -> Unit = {}): ByteArray {
            val bo = ByteArrayOutputStream(64)
            val d = DataOutputStream(bo)
            d.writeByte(type)
            d.body()
            return bo.toByteArray()
        }

        fun send(type: Int, body: DataOutputStream.() -> Unit = {}) {
            if (!alive) return
            try { ch.write(frame(type, body)) } catch (e: Exception) { close("write: $e") }
        }

        fun afterReady(action: (Link) -> Unit, fail: (String) -> Unit) {
            if (ready) { action(this); return }
            if (!alive) { fail("Connection closed"); return }
            waiting.add(action to fail)
            if (ready) drainWaiting()
            if (!alive) drainWaiting(failed = "Connection closed")
        }

        private fun drainWaiting(failed: String? = null) {
            val items = waiting.toList(); waiting.clear()
            items.forEach { (ok, no) -> if (failed == null) io.execute { ok(this) } else no(failed) }
        }

        fun pingIfIdle() {
            if (System.currentTimeMillis() - lastRx > 9000) send(T_PING)
        }

        fun close(reason: String) {
            if (!alive) return
            alive = false
            log("link $peerId closed: $reason")
            runCatching { socket.close() }
            if (counted) { counted = false; unpairedLinks.decrementAndGet() }
            _pairing.value = _pairing.value.filter { it.peerId != peerId }
            drainWaiting(failed = "Connection closed")
            outs.values.filter { it.link === this && !it.finished }.forEach { it.fail("Connection lost") }
            ins.values.filter { it.link === this && !it.finished }.forEach { abortIncoming(it, "Connection lost", TransferState.FAILED) }
            synchronized(links) { if (links[peerId] === this) links.remove(peerId) }
            publishPeers()
        }

        // ---- reading --------------------------------------------------------------------------

        fun run() {
            try {
                sendHello()
                while (alive) {
                    val f = ch.read()
                    lastRx = System.currentTimeMillis()
                    handle(f)
                }
            } catch (e: EOFException) {
                close("eof")
            } catch (e: Exception) {
                close(e.toString())
            }
        }

        fun hello() = sendHello()

        private fun sendHello() = send(T_HELLO) {
            writeByte(1)
            writeByte(platform.code)
            writeBoolean(trust.isTrusted(peerId))
            writeUTF(config.name.take(60))
        }

        private fun handle(f: ByteArray) {
            val d = DataInputStream(f.inputStream(1, f.size - 1))
            val type = f[0].toInt()
            if (!ready && type != T_HELLO && type != T_PAIR_OK && type != T_PAIR_NO && type != T_PING && type != T_PONG) {
                throw IOException("unexpected message $type before pairing")
            }
            when (type) {
                T_HELLO -> {
                    d.readByte()
                    platform = Platform.of(d.readByte().toInt())
                    val trustsMe = d.readBoolean()
                    name = d.readUTF()
                    if (trust.isTrusted(peerId)) trust.add(peerId, name)
                    if (!gotHello) {
                        gotHello = true
                        if (trust.isTrusted(peerId) && trustsMe) becomeReady()
                        else {
                            val req = PairingRequest(peerId, name, platform, code)
                            _pairing.value = _pairing.value.filter { it.peerId != peerId } + req
                            _events.tryEmit(NodeEvent.Pairing(req))
                            publishPeers()
                        }
                    }
                    publishPeers()
                }
                T_PAIR_OK -> { remoteOk = true; maybePaired() }
                T_PAIR_NO -> close("pairing declined")
                T_PING -> send(T_PONG)
                T_PONG -> {}
                T_TEXT -> {
                    val id = d.readLong()
                    val clip = d.readByte().toInt() and 1 != 0
                    val bytes = ByteArray(d.readInt()).also { d.readFully(it) }
                    val item = TextItem(id, peerId, name, String(bytes, Charsets.UTF_8), true, clip, System.currentTimeMillis())
                    _texts.value = (listOf(item) + _texts.value).take(200)
                    _events.tryEmit(NodeEvent.TextReceived(item))
                }
                T_OFFER -> onOffer(d)
                T_ANSWER -> {
                    val x = outs[d.readLong()] ?: return
                    x.answer.complete(d.readBoolean())
                }
                T_DATA -> {
                    val id = ByteArrayLong(f, 1)
                    val x = ins[id] ?: return
                    if (x.state != TransferState.ACTIVE) return
                    val sink = x.sink ?: return
                    try {
                        sink.out.write(f, 9, f.size - 9)
                        x.transferred += f.size - 9
                        markDirty()
                    } catch (e: Exception) {
                        abortIncoming(x, "Write failed: ${e.message}", TransferState.FAILED)
                        sendCancel(id)
                    }
                }
                T_END -> {
                    val x = ins[d.readLong()] ?: return
                    finishIncoming(x)
                }
                T_CANCEL -> {
                    val id = d.readLong()
                    outs[id]?.let { it.cancel(); return }
                    ins[id]?.let { abortIncoming(it, "canceled by sender", TransferState.CANCELED) }
                }
                T_DONE -> {
                    val x = outs[d.readLong()] ?: return
                    x.transferred = x.size
                    x.end(TransferState.DONE)
                }
                else -> {}
            }
        }

        // ---- pairing -------------------------------------------------------------------------

        fun answerPairing(accept: Boolean) {
            if (ready) return
            if (!accept) { send(T_PAIR_NO); close("pairing declined locally"); return }
            localOk = true
            send(T_PAIR_OK)
            maybePaired()
        }

        private fun maybePaired() {
            if (localOk && remoteOk && !ready) {
                trust.add(peerId, name)
                _events.tryEmit(NodeEvent.Paired(peerId, name))
                becomeReady()
            }
        }

        private fun becomeReady() {
            ready = true
            if (counted) { counted = false; unpairedLinks.decrementAndGet() }
            _pairing.value = _pairing.value.filter { it.peerId != peerId }
            publishPeers()
            drainWaiting()
        }

        // ---- text ----------------------------------------------------------------------------

        fun sendText(text: String, clipboard: Boolean) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size > 512 * 1024) return
            val id = Crypto.random.nextLong()
            send(T_TEXT) { writeLong(id); writeByte(if (clipboard) 1 else 0); writeInt(bytes.size); write(bytes) }
            val item = TextItem(id, peerId, name, text, false, clipboard, System.currentTimeMillis())
            _texts.value = (listOf(item) + _texts.value).take(200)
        }

        // ---- sending files ---------------------------------------------------------------------

        fun sendBatch(batch: List<OutXfer>) {
            for (x in batch) {
                x.link = this
                send(T_OFFER) { writeLong(x.id); writeLong(x.size); writeUTF(x.name); writeUTF(x.file.mime) }
            }
            for (x in batch) {
                if (!alive) { x.fail("Connection lost"); continue }
                if (x.finished) continue
                val accepted = try { x.answer.get(10, TimeUnit.MINUTES) } catch (e: Exception) { false }
                if (x.finished) continue
                if (!accepted) { x.end(TransferState.DECLINED); continue }
                stream(x)
            }
        }

        private fun stream(x: OutXfer) {
            x.state = TransferState.ACTIVE
            markDirty()
            try {
                x.file.open().use { input ->
                    val buf = ByteArray(9 + CHUNK)
                    buf[0] = T_DATA.toByte()
                    for (i in 0 until 8) buf[1 + i] = (x.id ushr (56 - 8 * i)).toByte()
                    while (true) {
                        if (x.canceled || !alive) return
                        val n = input.read(buf, 9, CHUNK)
                        if (n < 0) break
                        if (n == 0) continue
                        ch.write(buf, 0, 9 + n)
                        x.transferred += n
                        markDirty()
                    }
                }
                if (x.canceled) return
                send(T_END) { writeLong(x.id) }
            } catch (e: Exception) {
                if (!x.finished) {
                    x.fail(e.message ?: e.toString())
                    sendCancel(x.id)
                }
            }
        }

        fun sendCancel(id: Long) = send(T_CANCEL) { writeLong(id) }

        // ---- receiving files ---------------------------------------------------------------------

        private fun onOffer(d: DataInputStream) {
            val id = d.readLong()
            val size = d.readLong()
            val fname = d.readUTF()
            val mime = d.readUTF()
            val x = InXfer(id, this, safePath(fname).joinToString("/"), size, mime)
            ins[id] = x
            if (trust.isTrusted(peerId) && config.autoAccept) {
                answerOffer(x, true)
            } else {
                x.needsAnswer = true
                markDirty(); publishTransfers()
                _events.tryEmit(NodeEvent.Offer(x.info()))
            }
        }

        fun answerOffer(x: InXfer, accept: Boolean) {
            if (x.finished || x.state != TransferState.OFFERED) return
            if (!accept) {
                send(T_ANSWER) { writeLong(x.id); writeBoolean(false) }
                x.end(TransferState.DECLINED)
                return
            }
            try {
                x.sink = receiver.open(x.name, x.size, x.mime)
            } catch (e: Exception) {
                send(T_ANSWER) { writeLong(x.id); writeBoolean(false) }
                x.end(TransferState.FAILED, "Cannot create file: ${e.message}")
                return
            }
            x.needsAnswer = false
            x.state = TransferState.ACTIVE
            send(T_ANSWER) { writeLong(x.id); writeBoolean(true) }
            markDirty()
            if (x.size == 0L) finishIncoming(x)
        }

        fun finishIncoming(x: InXfer) {
            if (x.finished) return
            val sink = x.sink
            if (sink == null || x.transferred != x.size) {
                abortIncoming(x, "Incomplete file", TransferState.FAILED)
                sendCancel(x.id)
                return
            }
            try {
                x.result = sink.finish()
                x.end(TransferState.DONE)
                publishTransfers()
                _events.tryEmit(NodeEvent.FileReceived(x.info()))
                send(T_DONE) { writeLong(x.id) }
            } catch (e: Exception) {
                abortIncoming(x, "Save failed: ${e.message}", TransferState.FAILED)
                sendCancel(x.id)
            }
        }

        fun abortIncoming(x: InXfer, why: String, state: TransferState) {
            if (x.finished) return
            runCatching { x.sink?.abort() }
            x.end(state, why)
        }
    }

    private fun ByteArrayLong(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xff)
        return v
    }

    companion object {
        const val CHUNK = 256 * 1024
        private const val T_HELLO = 1
        private const val T_PAIR_OK = 2
        private const val T_PAIR_NO = 3
        private const val T_TEXT = 4
        private const val T_OFFER = 5
        private const val T_ANSWER = 6
        private const val T_DATA = 7
        private const val T_END = 8
        private const val T_CANCEL = 9
        private const val T_DONE = 10
        private const val T_PING = 11
        private const val T_PONG = 12
    }
}
