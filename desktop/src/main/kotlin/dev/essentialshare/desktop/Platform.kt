package dev.essentialshare.desktop

import dev.essentialshare.core.FileReceiver
import dev.essentialshare.core.OutgoingFile
import dev.essentialshare.core.ShareNode
import dev.essentialshare.core.SinkHandle
import dev.essentialshare.core.Store
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Settings and identity keys in %APPDATA%\Essential Share\settings.properties. */
class Settings(private val file: File) : Store {
    private val props = Properties()

    init {
        file.parentFile.mkdirs()
        runCatching { file.reader(Charsets.UTF_8).use { props.load(it) } }
    }

    @Synchronized override fun get(key: String): String? = props.getProperty(key)

    @Synchronized override fun put(key: String, value: String) {
        props.setProperty(key, value)
        runCatching { file.writer(Charsets.UTF_8).use { props.store(it, "Essential Share") } }
    }

    fun bool(key: String, def: Boolean) = get(key)?.toBooleanStrictOrNull() ?: def
    fun putBool(key: String, v: Boolean) = put(key, v.toString())

    companion object {
        fun default(): Settings {
            val base = System.getenv("APPDATA")?.let { File(it, "Essential Share") } ?: File(System.getProperty("user.home"), ".essential-share")
            return Settings(File(base, "settings.properties"))
        }

        fun defaultSaveDir() = File(System.getProperty("user.home"), "Downloads/Essential Share")
        fun defaultDeviceName(): String = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Windows PC"
    }
}

private val RESERVED = Regex("^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?$", RegexOption.IGNORE_CASE)

/** Writes received files to the save folder as `name.part`, renamed when complete; never overwrites. */
class DesktopReceiver(private val dir: () -> File) : FileReceiver {
    override fun open(name: String, size: Long, mime: String): SinkHandle {
        val root = dir().also { it.mkdirs() }.canonicalFile
        val segments = name.split('/').map { if (RESERVED.matches(it)) "_$it" else it }
        var target = segments.fold(root) { f, s -> File(f, s) }
        if (!target.canonicalPath.startsWith(root.path)) target = File(root, segments.last())
        target.parentFile.mkdirs()
        target = unique(target)
        val part = File(target.path + ".part")
        val os: OutputStream = part.outputStream().buffered(1 shl 17)
        val finalTarget = target
        return object : SinkHandle {
            override val out: OutputStream = os
            override fun finish(): String {
                os.close()
                Files.move(part.toPath(), finalTarget.toPath(), StandardCopyOption.REPLACE_EXISTING)
                return finalTarget.path
            }

            override fun abort() {
                runCatching { os.close() }
                part.delete()
            }
        }
    }

    private fun unique(f: File): File {
        if (!f.exists() && !File(f.path + ".part").exists()) return f
        val base = f.nameWithoutExtension
        val ext = f.extension.let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (true) {
            val c = File(f.parentFile, "$base ($i)$ext")
            if (!c.exists() && !File(c.path + ".part").exists()) return c
            i++
        }
    }
}

class DiskFile(private val file: File, override val name: String = file.name) : OutgoingFile {
    override val size: Long get() = file.length()
    override val mime: String get() = runCatching { Files.probeContentType(file.toPath()) }.getOrNull() ?: "application/octet-stream"
    override fun open(): InputStream = file.inputStream().buffered(1 shl 17)
}

/** Expand files and folders into a flat list (folders keep their relative paths in the name). */
fun expandPaths(paths: List<File>): List<OutgoingFile> {
    val out = ArrayList<OutgoingFile>()
    for (f in paths) {
        if (f.isDirectory) {
            val root = f.parentFile
            f.walkTopDown().filter { it.isFile }.take(5000).forEach { c ->
                out.add(DiskFile(c, c.relativeTo(root).path.replace('\\', '/')))
            }
        } else if (f.isFile) out.add(DiskFile(f))
    }
    return out
}

object Shell {
    fun open(path: String) = runCatching { Desktop.getDesktop().open(File(path)) }

    fun reveal(path: String) {
        runCatching { ProcessBuilder("explorer.exe", "/select,${File(path).absolutePath}").start() }
    }

    fun openFolder(dir: File) = runCatching { dir.mkdirs(); Desktop.getDesktop().open(dir) }

    /** Path of the packaged launcher, or null when running from Gradle/an IDE. */
    fun exePath(): String? = ProcessHandle.current().info().command().orElse(null)?.takeIf { it.endsWith("Essential Share.exe", true) }

    private fun reg(vararg args: String): Boolean =
        runCatching { ProcessBuilder(listOf("reg") + args).redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor() == 0 }.getOrDefault(false)

    /** Registry values go in through a UTF-16 .reg import: quotes passed on reg.exe's command line get mangled. */
    private fun importReg(body: String): Boolean {
        val file = File.createTempFile("essential", ".reg")
        return try {
            val text = "Windows Registry Editor Version 5.00\r\n\r\n$body"
            file.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE))
            reg("import", file.absolutePath)
        } finally {
            file.delete()
        }
    }

    /** Escapes a value for a .reg file. */
    private fun esc(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"")

    private const val RUN_PATH = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"

    fun setAutostart(on: Boolean): Boolean {
        val exe = exePath() ?: return false
        if (!on) return reg("delete", "HKCU\\$RUN_PATH", "/v", "EssentialShare", "/f")
        return importReg("[HKEY_CURRENT_USER\\$RUN_PATH]\r\n\"EssentialShare\"=\"${esc("\"$exe\" --tray")}\"\r\n\r\n")
    }

    private val MENU_KEYS = listOf("*", "Directory").map { "Software\\Classes\\$it\\shell\\EssentialShare" }

    fun hasContextMenu() = reg("query", "HKCU\\${MENU_KEYS[0]}")

    /**
     * Adds "Send via Essential Share" straight to the right-click menu of files and folders (current user only,
     * no admin rights). Windows 11 shows classic entries under "Show more options".
     */
    fun setContextMenu(on: Boolean, label: String): Boolean {
        // the old Send to entry is not needed any more
        File(System.getenv("APPDATA") ?: "", "Microsoft/Windows/SendTo/Essential Share.lnk").delete()
        if (!on) return MENU_KEYS.map { reg("delete", "HKCU\\$it", "/f") }.all { it } || !hasContextMenu()
        val exe = exePath() ?: return false
        val sb = StringBuilder()
        for (key in MENU_KEYS) {
            val base = "HKEY_CURRENT_USER\\$key"
            sb.append("[$base]\r\n@=\"${esc(label)}\"\r\n\"Icon\"=\"${esc("\"$exe\",0")}\"\r\n\r\n")
            sb.append("[$base\\command]\r\n@=\"${esc("\"$exe\" \"%1\"")}\"\r\n\r\n")
        }
        return importReg(sb.toString())
    }
}

/** Mirrors the system clipboard text to trusted devices and applies text clipboards received from them. */
class ClipboardSync(private val node: ShareNode, private val enabled: () -> Boolean) {
    @Volatile private var last: String? = readText()
    @Volatile private var running = true

    private fun readText(): String? = try {
        val cb = Toolkit.getDefaultToolkit().systemClipboard
        if (cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) cb.getData(DataFlavor.stringFlavor) as? String else null
    } catch (_: Exception) {
        null
    }

    fun start() {
        Thread({
            while (running) {
                try {
                    Thread.sleep(500)
                    if (!enabled()) { last = readText(); continue }
                    val now = readText()
                    if (now != null && now != last && now.isNotBlank() && now.length < 200_000) {
                        last = now
                        node.sendClipboardToAll(now)
                    } else if (now != null) last = now
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Exception) {
                }
            }
        }, "es-clipboard").apply { isDaemon = true; start() }
    }

    fun stop() { running = false }

    fun apply(text: String) {
        last = text
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    fun current(): String? = readText()
}

/**
 * One window per user: a second launch (for example from Explorer's Send to menu) hands its file list to the
 * running instance over a loopback socket and exits.
 */
object SingleInstance {
    private const val PORT = 47892

    private var server: ServerSocket? = null

    /**
     * Claims the single-instance port before anything else starts. Returns true when this process is the
     * first one; otherwise it hands [args] to the running instance (retrying while that one is still starting).
     */
    fun claimOrHandOff(args: List<String>): Boolean {
        repeat(20) {
            server = runCatching { ServerSocket(PORT, 16, InetAddress.getLoopbackAddress()) }.getOrNull()
            if (server != null) return true
            if (send(args)) return false
            Thread.sleep(250)
        }
        return true
    }

    private fun send(args: List<String>): Boolean = try {
        Socket(InetAddress.getLoopbackAddress(), PORT).use { s ->
            s.getOutputStream().bufferedWriter(Charsets.UTF_8).use { w ->
                w.write("ESHARE\n"); args.forEach { w.write(it + "\n") }
            }
        }
        true
    } catch (_: Exception) {
        false
    }

    fun listen(onMessage: (List<String>) -> Unit) {
        val server = server ?: return
        Thread({
            while (true) {
                try {
                    server.accept().use { s ->
                        val lines = s.getInputStream().bufferedReader(Charsets.UTF_8).readLines()
                        if (lines.firstOrNull() == "ESHARE") onMessage(lines.drop(1).filter { it.isNotBlank() })
                    }
                } catch (_: Exception) {
                }
            }
        }, "es-single-instance").apply { isDaemon = true; start() }
    }
}
