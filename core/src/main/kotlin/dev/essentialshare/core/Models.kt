package dev.essentialshare.core

import java.io.InputStream
import java.io.OutputStream

enum class Platform(val code: Int) {
    OTHER(0), ANDROID(1), WINDOWS(2), MAC(3), LINUX(4);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: OTHER
        fun detect(): Platform {
            val os = System.getProperty("os.name", "").lowercase()
            return when {
                "win" in os -> WINDOWS
                "mac" in os -> MAC
                "linux" in os || "nux" in os -> LINUX
                else -> OTHER
            }
        }
    }
}

enum class LinkState { OFFLINE, SEEN, CONNECTING, PAIRING, READY }

data class PeerInfo(
    val id: String,
    val name: String,
    val platform: Platform,
    val address: String?,
    val port: Int,
    val trusted: Boolean,
    val state: LinkState,
)

enum class Direction { IN, OUT }

enum class TransferState { OFFERED, ACTIVE, DONE, FAILED, CANCELED, DECLINED }

data class TransferInfo(
    val id: Long,
    val direction: Direction,
    val peerId: String,
    val peerName: String,
    val name: String,
    val size: Long,
    val transferred: Long,
    val state: TransferState,
    /** bytes per second, smoothed */
    val speed: Long,
    val startedAt: Long,
    val finishedAt: Long,
    /** where a received file ended up (path or content uri) */
    val result: String?,
    val error: String?,
    /** incoming offer waiting for the user's decision */
    val needsAnswer: Boolean,
) {
    val fraction: Float
        get() = if (size <= 0) (if (state == TransferState.DONE) 1f else 0f)
        else (transferred.toDouble() / size).toFloat().coerceIn(0f, 1f)
    val finished: Boolean
        get() = state == TransferState.DONE || state == TransferState.FAILED ||
            state == TransferState.CANCELED || state == TransferState.DECLINED
}

data class TextItem(
    val id: Long,
    val peerId: String,
    val peerName: String,
    val text: String,
    val incoming: Boolean,
    val clipboard: Boolean,
    val time: Long,
)

data class PairingRequest(val peerId: String, val peerName: String, val platform: Platform, val code: String)

sealed class NodeEvent {
    data class Pairing(val request: PairingRequest) : NodeEvent()
    data class Paired(val peerId: String, val name: String) : NodeEvent()
    data class Offer(val transfer: TransferInfo) : NodeEvent()
    data class FileReceived(val transfer: TransferInfo) : NodeEvent()
    data class TextReceived(val item: TextItem) : NodeEvent()
    data class TransferFailed(val transfer: TransferInfo) : NodeEvent()
}

/** A file the host wants to send. [open] is called on a worker thread. */
interface OutgoingFile {
    val name: String
    val size: Long
    val mime: String
    fun open(): InputStream
}

/** Destination of one received file. */
interface SinkHandle {
    val out: OutputStream

    /** Close and publish the file, return its path or uri. */
    fun finish(): String

    /** Close and delete the partial file. */
    fun abort()
}

interface FileReceiver {
    fun open(name: String, size: Long, mime: String): SinkHandle
}

/** Tiny persistent key/value store supplied by the host. */
interface Store {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

class NodeConfig(@Volatile var name: String, @Volatile var autoAccept: Boolean = true)

/** Strip anything that could escape the target folder and keep forward-slash separated segments. */
fun safePath(name: String): List<String> {
    val bad = Regex("[<>:\"|?*\\u0000-\\u001f]")
    val parts = name.replace('\\', '/').split('/')
        .map { it.trim().replace(bad, "_").trimEnd('.', ' ') }
        .filter { it.isNotEmpty() && it != "." && it != ".." }
    return if (parts.isEmpty()) listOf("file") else parts.takeLast(8)
}
