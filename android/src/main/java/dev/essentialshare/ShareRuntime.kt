package dev.essentialshare

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.ContextCompat
import dev.essentialshare.core.FileReceiver
import dev.essentialshare.core.Identity
import dev.essentialshare.core.NodeConfig
import dev.essentialshare.core.NodeEvent
import dev.essentialshare.core.OutgoingFile
import dev.essentialshare.core.Platform
import dev.essentialshare.core.ShareNode
import dev.essentialshare.core.SinkHandle
import dev.essentialshare.core.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class PrefsStore(private val p: SharedPreferences) : Store {
    override fun get(key: String): String? = p.getString(key, null)
    override fun put(key: String, value: String) { p.edit().putString(key, value).apply() }
}

/** Saves received files into Downloads/Essential Share through MediaStore (no storage permission needed). */
class MediaStoreReceiver(private val ctx: Context) : FileReceiver {
    /** "name.ext" -> "name (1).ext", "name (2).ext"...: the extension always stays last. */
    private fun uniqueName(resolver: android.content.ContentResolver, relPath: String, file: String): String {
        val base = file.substringBeforeLast('.', file)
        val ext = file.substringAfterLast('.', "").let { if (it.isEmpty() || it == file) "" else ".$it" }
        val args = android.os.Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME}=?")
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        fun taken(n: String): Boolean = runCatching {
            args.putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$relPath/", n))
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID), args, null)?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
        var name = file
        var i = 1
        while (taken(name) && i < 1000) name = "$base ($i)$ext".also { i++ }
        return name
    }

    override fun open(name: String, size: Long, mime: String): SinkHandle {
        val parts = name.split('/')
        val sub = parts.dropLast(1).joinToString("/")
        val resolver = ctx.contentResolver
        val relPath = "Download/Essential Share" + if (sub.isNotEmpty()) "/$sub" else ""
        val file = uniqueName(resolver, relPath, parts.last())
        // the type follows the extension: a wrong or generic type makes MediaStore rewrite the name
        val ext = file.substringAfterLast('.', "").lowercase()
        val mimeOut = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: mime.takeIf { it.isNotBlank() && it != "application/octet-stream" } ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file)
            put(MediaStore.Downloads.MIME_TYPE, mimeOut)
            put(MediaStore.Downloads.RELATIVE_PATH, relPath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore refused the file")
        val os = resolver.openOutputStream(uri) ?: error("Cannot open $uri")
        val buffered = os.buffered(1 shl 17)
        return object : SinkHandle {
            override val out: OutputStream = buffered
            override fun finish(): String {
                buffered.close()
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                return uri.toString()
            }

            override fun abort() {
                runCatching { buffered.close() }
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }
}

/** A content Uri opened up front, so it stays readable after the share intent's grant is gone. */
class UriFile private constructor(
    override val name: String, override val size: Long, override val mime: String, private val open: () -> InputStream,
) : OutgoingFile {
    override fun open(): InputStream = open.invoke()

    companion object {
        /** Returns null if the Uri cannot be read. */
        fun from(ctx: Context, uri: Uri, fallbackMime: String? = null): UriFile? = runCatching {
            val r = ctx.contentResolver
            var name: String? = null
            var size = -1L
            r.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
            val mime = r.getType(uri) ?: fallbackMime ?: "application/octet-stream"
            val fileName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            val pfd: ParcelFileDescriptor = r.openFileDescriptor(uri, "r") ?: return null
            if (size < 0) size = pfd.statSize
            if (size < 0) {
                // a stream without a known length: copy it once so the receiver gets an exact size
                val tmp = File.createTempFile("send", ".tmp", ctx.cacheDir)
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                UriFile(fileName, tmp.length(), mime) { tmp.inputStream().buffered(1 shl 17) }
            } else UriFile(fileName, size, mime) { ParcelFileDescriptor.AutoCloseInputStream(pfd).buffered(1 shl 17) }
        }.getOrNull()
    }
}

/**
 * Process-wide holder of the [ShareNode]. The node runs while something holds it: an open screen, or the
 * foreground service when "always ready" is on.
 */
object Rt {
    private lateinit var app: Application
    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var config: NodeConfig
        private set
    lateinit var node: ShareNode
        private set

    private var refs = 0
    private var stopPending: Runnable? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val _uiVisible = MutableStateFlow(0)
    val uiVisible: StateFlow<Int> = _uiVisible

    fun init(application: Application) {
        app = application
        prefs = app.getSharedPreferences("essential_share", Context.MODE_PRIVATE)
        val store = PrefsStore(prefs)
        config = NodeConfig(prefs.getString("deviceName", null) ?: defaultName(), prefs.getBoolean("autoAccept", true))
        node = ShareNode(Identity.load(store), store, config, MediaStoreReceiver(app), Platform.ANDROID) { Log.d("EssentialShare", it) }
        dev.essentialshare.ui.ThemeState.mode = themeMode
        scope.launch { node.events.collect { Notifier.onEvent(app, it) } }
    }

    private fun defaultName(): String {
        val n = runCatching { android.provider.Settings.Global.getString(app.contentResolver, "device_name") }.getOrNull()
        return n?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
    }

    var deviceName: String
        get() = config.name
        set(v) {
            config.name = v.trim().take(40).ifEmpty { config.name }
            prefs.edit().putString("deviceName", config.name).apply()
            node.announceName()
        }

    var autoAccept: Boolean
        get() = config.autoAccept
        set(v) { config.autoAccept = v; prefs.edit().putBoolean("autoAccept", v).apply() }

    var alwaysReady: Boolean
        get() = prefs.getBoolean("alwaysReady", true)
        set(v) {
            prefs.edit().putBoolean("alwaysReady", v).apply()
            if (v) ShareService.start(app) else ShareService.stop(app)
        }

    var applyClipboard: Boolean
        get() = prefs.getBoolean("applyClipboard", true)
        set(v) { prefs.edit().putBoolean("applyClipboard", v).apply() }

    var themeMode: dev.essentialshare.ui.ThemeMode
        get() = runCatching { dev.essentialshare.ui.ThemeMode.valueOf(prefs.getString("theme", null) ?: "SYSTEM") }.getOrDefault(dev.essentialshare.ui.ThemeMode.SYSTEM)
        set(v) { prefs.edit().putString("theme", v.name).apply(); dev.essentialshare.ui.ThemeState.mode = v }

    var firstRunDone: Boolean
        get() = prefs.getBoolean("firstRun", false)
        set(v) { prefs.edit().putBoolean("firstRun", v).apply() }

    fun uiStarted() { _uiVisible.value += 1; acquire() }
    fun uiStopped() { _uiVisible.value -= 1; release() }

    @Synchronized fun acquire() {
        stopPending?.let { main.removeCallbacks(it) }
        stopPending = null
        refs++
        if (!node.isRunning) {
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("essential-share").apply { setReferenceCounted(false); acquire() }
            Thread { runCatching { node.start() }.onFailure { Log.e("EssentialShare", "start failed", it) } }.start()
        }
    }

    @Synchronized fun release() {
        refs = (refs - 1).coerceAtLeast(0)
        if (refs == 0) {
            val r = Runnable {
                synchronized(this) {
                    if (refs == 0) {
                        node.stop()
                        runCatching { multicastLock?.release() }
                    }
                }
            }
            stopPending = r
            main.postDelayed(r, 20_000)
        }
    }

    fun setClipboard(ctx: Context, text: String) {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Essential Share", text))
    }

    fun powerManager(ctx: Context): PowerManager = ctx.getSystemService(PowerManager::class.java)

    fun ensureService() { if (alwaysReady) ShareService.start(app) }

    fun hasPermission(p: String) = ContextCompat.checkSelfPermission(app, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
}
