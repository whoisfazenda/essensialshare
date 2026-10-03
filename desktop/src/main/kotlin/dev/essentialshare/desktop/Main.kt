package dev.essentialshare.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import dev.essentialshare.core.Identity
import dev.essentialshare.core.NodeConfig
import dev.essentialshare.core.NodeEvent
import dev.essentialshare.core.Platform
import dev.essentialshare.core.ShareNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

/** Windows app theme: AppsUseLightTheme = 0 means dark. */
fun windowsUsesDarkTheme(): Boolean = runCatching {
    val p = ProcessBuilder("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme").redirectErrorStream(true).start()
    p.inputStream.bufferedReader().readText().contains("0x0")
}.getOrDefault(false)

/** The same icon as the Android launcher, used for the tray and the window. */
val AppIcon: Painter by lazy { BitmapPainter(useResource("icon.png", ::loadImageBitmap)) }

class Controller(private val scope: CoroutineScope) : Actions {
    val settings = Settings.default()
    private val config = NodeConfig(
        settings.get("deviceName") ?: Settings.defaultDeviceName(),
        settings.bool("autoAccept", true),
    )
    var saveDir by mutableStateOf(File(settings.get("saveDir") ?: Settings.defaultSaveDir().path))
    val node = ShareNode(Identity.load(settings), settings, config, DesktopReceiver { saveDir }, Platform.WINDOWS) { println(it) }
    val clipboard = ClipboardSync(node) { settings.bool("clipboardSync", false) }

    var selectedId by mutableStateOf<String?>(null)
    var settingsOpen by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var pending by mutableStateOf<List<File>>(emptyList())
    var deviceName by mutableStateOf(config.name)
    var settingsState by mutableStateOf(readSettings())
    var visible by mutableStateOf(true)
    var focusTick by mutableStateOf(0)

    init {
        ThemeState.mode = runCatching { ThemeMode.valueOf(settings.get("theme") ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM)
        ThemeState.systemDark = windowsUsesDarkTheme()
    }

    var langCode by mutableStateOf(settings.get("lang") ?: "")

    init { Lang.apply(langCode) }

    override fun setLanguage(code: String) {
        langCode = code
        settings.put("lang", code)
        Lang.apply(code)
    }

    override fun setTheme(mode: ThemeMode) {
        ThemeState.mode = mode
        ThemeState.systemDark = windowsUsesDarkTheme()
        settings.put("theme", mode.name)
    }

    private fun readSettings() = SettingsState(
        autoAccept = config.autoAccept,
        clipboardSync = settings.bool("clipboardSync", false),
        minimizeToTray = settings.bool("minimizeToTray", true),
        autostart = settings.bool("autostart", false),
        sendTo = Shell.hasContextMenu(),
        canIntegrate = Shell.exePath() != null,
    )

    fun start() {
        runCatching { node.start() }.onFailure { message = it.message }
        clipboard.start()
        // packaged app: add the right-click entry on the first launch, and keep its path current if the folder moves
        if (Shell.exePath() != null) {
            scope.launch(Dispatchers.IO) {
                val first = !settings.bool("menuInstalled", false)
                if (first || Shell.hasContextMenu()) {
                    settings.putBool("menuInstalled", true)
                    Shell.setContextMenu(true, tr("Send via Essential Share", "Отправить через Essential Share"))
                    settingsState = readSettings()
                }
            }
        }
    }

    fun say(text: String) { message = text }

    fun bringToFront() { visible = true; focusTick++ }

    // ---- actions ----------------------------------------------------------------------------

    override fun select(id: String?) {
        selectedId = id
        val peer = node.peers.value.firstOrNull { it.id == id }
        if (id != null && peer != null && peer.state == dev.essentialshare.core.LinkState.SEEN) node.connectPeer(id)
        if (id != null && pending.isNotEmpty()) { sendTo(id, pending); pending = emptyList() }
    }

    override fun toggleSettings() { settingsOpen = !settingsOpen }

    private fun sendTo(id: String, files: List<File>) {
        scope.launch(Dispatchers.IO) {
            val list = expandPaths(files)
            if (list.isEmpty()) { say(tr("Nothing to send", "Нечего отправлять")); return@launch }
            node.sendFiles(id, list)
        }
    }

    private fun target(): String? {
        selectedId?.let { return it }
        val online = node.peers.value.filter { it.state != dev.essentialshare.core.LinkState.OFFLINE }
        if (online.size == 1) return online[0].id
        say(tr("Select a device first", "Сначала выберите устройство"))
        return null
    }

    override fun drop(files: List<File>) {
        val id = target() ?: return
        sendTo(id, files)
    }

    override fun pickFiles() {
        val id = target() ?: return
        scope.launch(Dispatchers.IO) {
            val d = FileDialog(null as Frame?, tr("Send files", "Отправить файлы"), FileDialog.LOAD)
            d.isMultipleMode = true
            d.isVisible = true
            val files = d.files?.toList().orEmpty()
            if (files.isNotEmpty()) sendTo(id, files)
        }
    }

    override fun pickFolder() {
        val id = target() ?: return
        scope.launch(Dispatchers.IO) {
            val c = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; dialogTitle = tr("Send folder", "Отправить папку") }
            if (c.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) sendTo(id, listOf(c.selectedFile))
        }
    }

    override fun sendClipboard() {
        val id = target() ?: return
        val text = clipboard.current()
        if (text.isNullOrBlank()) { say(tr("Clipboard has no text", "В буфере нет текста")); return }
        node.sendText(id, text, clipboard = true)
        say(tr("Clipboard sent", "Буфер отправлен"))
    }

    override fun sendText(text: String) {
        val id = target() ?: return
        node.sendText(id, text, clipboard = false)
    }

    override fun accept(id: Long, ok: Boolean) = node.answerOffer(id, ok)
    override fun cancel(id: Long) = node.cancel(id)
    override fun pair(peerId: String, ok: Boolean) = node.answerPairing(peerId, ok)
    override fun unpair(peerId: String) { node.unpair(peerId) }
    override fun open(path: String) { Shell.open(path) }
    override fun reveal(path: String) = Shell.reveal(path)
    override fun copy(text: String) { clipboard.apply(text); say(tr("Copied", "Скопировано")) }

    override fun rename(name: String) {
        val n = name.trim().take(40)
        if (n.isEmpty()) return
        config.name = n
        deviceName = n
        settings.put("deviceName", n)
        node.announceName()
    }

    override fun setAutoAccept(v: Boolean) { config.autoAccept = v; settings.putBool("autoAccept", v); settingsState = readSettings() }
    override fun setClipboardSync(v: Boolean) { settings.putBool("clipboardSync", v); settingsState = readSettings() }
    override fun setMinimizeToTray(v: Boolean) { settings.putBool("minimizeToTray", v); settingsState = readSettings() }

    override fun setAutostart(v: Boolean) {
        if (Shell.setAutostart(v)) settings.putBool("autostart", v) else say(tr("Could not change autostart", "Не удалось изменить автозапуск"))
        settingsState = readSettings()
    }

    override fun setSendTo(v: Boolean) {
        if (!Shell.setContextMenu(v, tr("Send via Essential Share", "Отправить через Essential Share"))) say(tr("Could not change the right-click menu", "Не удалось изменить меню правой кнопки"))
        settingsState = readSettings()
    }

    override fun connectIp(host: String) {
        val i = host.lastIndexOf(':')
        val port = host.substring(i + 1).toIntOrNull()
        if (i <= 0 || port == null) { say(tr("Use the form ip:port", "Формат: ip:порт")); return }
        say(tr("Connecting…", "Подключаемся…"))
        node.connectManually(host.substring(0, i), port) { ok -> say(if (ok) tr("Connected", "Подключено") else tr("Could not connect", "Не удалось подключиться")) }
    }

    override fun openSaveDir() { Shell.openFolder(saveDir) }

    override fun changeSaveDir() {
        scope.launch(Dispatchers.IO) {
            val c = JFileChooser(saveDir).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; dialogTitle = tr("Save folder", "Папка для файлов") }
            if (c.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                saveDir = c.selectedFile
                settings.put("saveDir", c.selectedFile.path)
            }
        }
    }

    override fun clearFinished() = node.clearFinished()
    override fun cancelPending() { pending = emptyList() }

    private val incomingPaths = java.util.concurrent.CopyOnWriteArrayList<File>()

    /** Explorer starts one process per selected file; collect them for a moment and treat them as one batch. */
    fun receive(paths: List<String>) {
        incomingPaths.addAll(paths.map(::File).filter { it.exists() })
        scope.launch {
            delay(450)
            val files = incomingPaths.toList().also { incomingPaths.clear() }
            if (files.isEmpty()) return@launch
            settingsOpen = false
            val online = node.peers.value.filter { it.state != dev.essentialshare.core.LinkState.OFFLINE }
            if (online.size == 1) { sendTo(online[0].id, files); say(tr("Sending ${files.size} item(s)", "Отправляем: ${files.size}")) }
            else { pending = pending + files; bringToFront() }
        }
    }
}

fun main(args: Array<String>) {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val tray = "--tray" in args
    val paths = args.filter { !it.startsWith("--") }
    if (SingleInstance.handOff(paths)) return
    application {
        val scope = rememberCoroutineScope0()
        val c = remember { Controller(scope).also { it.start(); it.visible = !tray } }
        val trayState = rememberTrayState()
        LaunchedEffect(Unit) {
            SingleInstance.listen { msg -> if (msg.isEmpty()) c.bringToFront() else c.receive(msg) }
            if (paths.isNotEmpty()) c.receive(paths)
        }
        LaunchedEffect(Unit) {
            c.node.events.collect { e ->
                when (e) {
                    is NodeEvent.Pairing -> { c.bringToFront(); trayState.sendNotification(Notification(tr("Pairing request", "Запрос на сопряжение"), e.request.peerName)) }
                    is NodeEvent.Paired -> c.say(tr("Paired with ", "Сопряжено с ") + e.name)
                    is NodeEvent.Offer -> trayState.sendNotification(Notification(e.transfer.peerName, tr("wants to send ", "хочет отправить ") + e.transfer.name))
                    is NodeEvent.FileReceived -> trayState.sendNotification(Notification(tr("Received from ", "Получено от ") + e.transfer.peerName, e.transfer.name))
                    is NodeEvent.TextReceived -> {
                        if (e.item.clipboard) c.clipboard.apply(e.item.text)
                        trayState.sendNotification(Notification(e.item.peerName, if (e.item.clipboard) tr("Clipboard received", "Получен буфер обмена") else e.item.text.take(120)))
                    }
                    is NodeEvent.TransferFailed -> {}
                }
            }
        }
        val ui = UiState(
            deviceName = c.deviceName,
            peers = c.node.peers.collectAsState().value,
            transfers = c.node.transfers.collectAsState().value,
            texts = c.node.texts.collectAsState().value,
            pairing = c.node.pairing.collectAsState().value,
            selectedId = c.selectedId,
            settingsOpen = c.settingsOpen,
            settings = c.settingsState,
            saveDir = c.saveDir.path,
            pendingPaths = c.pending.size,
            message = c.message,
            language = c.langCode,
        )
        LaunchedEffect(c.message) { if (c.message != null) { delay(4000); c.message = null } }

        Tray(
            icon = AppIcon, tooltip = "Essential Share", onAction = { c.bringToFront() },
            menu = {
                Item(tr("Open", "Открыть"), onClick = { c.bringToFront() })
                Item(tr("Open save folder", "Открыть папку с файлами"), onClick = { c.openSaveDir() })
                CheckboxItem(tr("Share clipboard", "Делиться буфером обмена"), checked = c.settingsState.clipboardSync, onCheckedChange = { c.setClipboardSync(it) })
                Separator()
                Item(tr("Quit", "Выход"), onClick = {
                    c.clipboard.stop(); c.node.stop(); exitApplication()
                })
            },
        )

        val state = rememberWindowState(size = DpSize(1060.dp, 720.dp), position = WindowPosition(androidx.compose.ui.Alignment.Center))
        Window(
            onCloseRequest = {
                if (c.settingsState.minimizeToTray) c.visible = false else { c.clipboard.stop(); c.node.stop(); exitApplication() }
            },
            visible = c.visible, state = state, title = "Essential Share", icon = AppIcon,
            undecorated = true, resizable = false,
        ) {
            LaunchedEffect(c.focusTick) { if (c.focusTick > 0) { state.isMinimized = false; window.toFront(); window.requestFocus() } }
            val actions = remember(c) {
                object : Actions by c {
                    override fun minimize() { state.isMinimized = true }
                    override fun close() { window.dispatchEvent(java.awt.event.WindowEvent(window, java.awt.event.WindowEvent.WINDOW_CLOSING)) }
                }
            }
            AppScreen(ui, actions) { content -> WindowDraggableArea { content() } }
        }
    }
}

@Composable
private fun rememberCoroutineScope0(): CoroutineScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
