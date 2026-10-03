@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.essentialshare.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.essentialshare.core.Direction
import dev.essentialshare.core.LinkState
import dev.essentialshare.core.PairingRequest
import dev.essentialshare.core.PeerInfo
import dev.essentialshare.core.Platform
import dev.essentialshare.core.TextItem
import dev.essentialshare.core.TransferInfo
import dev.essentialshare.core.TransferState
import java.io.File
import java.net.URI

data class SettingsState(
    val autoAccept: Boolean = true,
    val clipboardSync: Boolean = false,
    val minimizeToTray: Boolean = true,
    val autostart: Boolean = false,
    val sendTo: Boolean = false,
    val canIntegrate: Boolean = false,
)

data class UiState(
    val deviceName: String,
    val peers: List<PeerInfo> = emptyList(),
    val transfers: List<TransferInfo> = emptyList(),
    val texts: List<TextItem> = emptyList(),
    val pairing: List<PairingRequest> = emptyList(),
    val selectedId: String? = null,
    val settingsOpen: Boolean = false,
    val settings: SettingsState = SettingsState(),
    val saveDir: String = "",
    val pendingPaths: Int = 0,
    val message: String? = null,
    val language: String = "",
)

interface Actions {
    fun select(id: String?) {}
    fun toggleSettings() {}
    fun pickFiles() {}
    fun pickFolder() {}
    fun sendClipboard() {}
    fun sendText(text: String) {}
    fun drop(files: List<File>) {}
    fun accept(id: Long, ok: Boolean) {}
    fun cancel(id: Long) {}
    fun pair(peerId: String, ok: Boolean) {}
    fun unpair(peerId: String) {}
    fun open(path: String) {}
    fun reveal(path: String) {}
    fun copy(text: String) {}
    fun rename(name: String) {}
    fun setAutoAccept(v: Boolean) {}
    fun setClipboardSync(v: Boolean) {}
    fun setMinimizeToTray(v: Boolean) {}
    fun setAutostart(v: Boolean) {}
    fun setSendTo(v: Boolean) {}
    fun connectIp(host: String) {}
    fun openSaveDir() {}
    fun changeSaveDir() {}
    fun clearFinished() {}
    fun cancelPending() {}
    fun setTheme(mode: ThemeMode) {}
    fun setLanguage(code: String) {}
    fun minimize() {}
    fun close() {}
}

@Composable
fun AppScreen(ui: UiState, act: Actions, titleBar: @Composable (@Composable () -> Unit) -> Unit = { it() }) {
    var dropping by remember { mutableStateOf(false) }
    val target = remember(act) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { dropping = true }
            override fun onExited(event: DragAndDropEvent) { dropping = false }
            override fun onEnded(event: DragAndDropEvent) { dropping = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dropping = false
                val d = event.dragData()
                if (d is DragData.FilesList) {
                    val files = d.readFiles().mapNotNull { runCatching { File(URI(it)) }.getOrNull() }
                    if (files.isNotEmpty()) { act.drop(files); return true }
                }
                return false
            }
        }
    }
    Column(Modifier.fillMaxSize().background(N.bg).dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target)) {
        titleBar { TitleBar(act) }
        Row(Modifier.fillMaxSize()) {
            LeftPane(ui, act, Modifier.width(340.dp).fillMaxHeight())
            Box(Modifier.width(1.dp).fillMaxHeight().background(N.border))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (ui.settingsOpen) SettingsPane(ui, act) else MainPane(ui, act, dropping)
                CrossMark(Modifier.align(Alignment.TopEnd).padding(10.dp))
                CrossMark(Modifier.align(Alignment.BottomEnd).padding(10.dp))
            }
        }
    }
    ui.pairing.firstOrNull()?.let { PairDialog(it, act) }
}

// ---- chrome ------------------------------------------------------------------------------------

@Composable
private fun TitleBar(act: Actions) {
    Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 20.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Logotype()
        Spacer(Modifier.weight(1f))
        NIconButton(Ic.MINIMIZE, act::minimize, size = 32.dp, tint = N.secondary)
        NIconButton(Ic.CLOSE, act::close, size = 32.dp, tint = N.secondary)
    }
}

// ---- left: devices -----------------------------------------------------------------------------

@Composable
private fun LeftPane(ui: UiState, act: Actions, modifier: Modifier) {
    Column(modifier.padding(start = 28.dp, end = 28.dp, top = 6.dp, bottom = 20.dp)) {
        NCaps(tr("This PC", "Этот ПК") + " · " + ui.deviceName, color = N.secondary)
        Spacer(Modifier.height(6.dp))
        NText(tr("Share", "Обмен"), style = NType.title)
        Spacer(Modifier.height(10.dp))
        ScanPanel(
            ui.peers.filter { it.state != LinkState.OFFLINE }.map { RadarDot(it.id, it.state == LinkState.READY, it.id == ui.selectedId, it.platform == Platform.ANDROID) },
            tr("Scanning", "Поиск"), Modifier.fillMaxWidth().height(132.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NCaps(tr("Nearby", "Рядом"))
            Spacer(Modifier.width(8.dp))
            NCaps(ui.peers.count { it.state != LinkState.OFFLINE }.toString(), color = N.display)
        }
        Spacer(Modifier.height(10.dp))
        if (ui.peers.isEmpty()) {
            Column(Modifier.nCard(24.dp).fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NText(tr("Looking for devices…", "Ищем устройства…"), style = NType.bodyMedium)
                NMeta(
                    tr("Open Essential Share on your phone. Both devices must be on the same Wi-Fi.", "Откройте Essential Share на телефоне. Оба устройства должны быть в одной сети Wi-Fi."),
                    maxLines = 4,
                )
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ui.peers, key = { it.id }) { p -> DeviceCard(p, p.id == ui.selectedId, ui.pendingPaths > 0) { act.select(p.id) } }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NIconButton(Ic.GEAR, act::toggleSettings, tint = if (ui.settingsOpen) N.display else N.secondary)
        }
    }
}

private fun stateLabel(p: PeerInfo): String = when (p.state) {
    LinkState.READY -> tr("Connected", "Подключено")
    LinkState.PAIRING -> tr("Pairing", "Сопряжение")
    LinkState.CONNECTING -> tr("Connecting", "Подключение")
    LinkState.SEEN -> if (p.trusted) tr("Online", "В сети") else tr("Tap to pair", "Нажмите для пары")
    LinkState.OFFLINE -> tr("Offline", "Не в сети")
}

@Composable
private fun DeviceCard(p: PeerInfo, selected: Boolean, picking: Boolean, onClick: () -> Unit) {
    val off = p.state == LinkState.OFFLINE
    Row(
        Modifier.fillMaxWidth().nCard(24.dp, outlined = selected || picking && !off, borderColor = N.ink).nClickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        NIcon(if (p.platform == Platform.ANDROID) Ic.PHONE else Ic.PC, tint = if (off) N.disabled else N.display)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            NText(p.name, style = NType.bodyMedium, color = if (off) N.secondary else N.display, maxLines = 1)
            NCaps(stateLabel(p), color = if (p.state == LinkState.READY) N.display else N.secondary)
        }
        StatusDot(p.state)
        if (p.trusted) NIcon(Ic.SHIELD, tint = N.secondary, size = 16.dp)
    }
}

@Composable
private fun StatusDot(s: LinkState) {
    Canvas(Modifier.size(10.dp)) {
        when (s) {
            LinkState.READY -> drawCircle(N.display)
            LinkState.SEEN, LinkState.CONNECTING, LinkState.PAIRING -> drawCircle(N.secondary, style = Stroke(1.5.dp.toPx()))
            LinkState.OFFLINE -> drawCircle(N.disabled.copy(alpha = 0.6f), radius = 2.dp.toPx())
        }
    }
}

// ---- right: send + activity ---------------------------------------------------------------------

@Composable
private fun MainPane(ui: UiState, act: Actions, dropping: Boolean) {
    val selected = ui.peers.firstOrNull { it.id == ui.selectedId }
    Column(
        Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 6.dp, bottom = 20.dp),
    ) {
        if (ui.pendingPaths > 0) {
            Row(
                Modifier.fillMaxWidth().nCard(24.dp, outlined = true).padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NIcon(Ic.SEND, size = 20.dp)
                NText(
                    tr("Pick a device on the left to send ${ui.pendingPaths} item(s)", "Выберите устройство слева, чтобы отправить: ${ui.pendingPaths}"),
                    Modifier.weight(1f), NType.bodyMedium, maxLines = 2,
                )
                NButton(tr("Cancel", "Отмена"), act::cancelPending, kind = BtnKind.GHOST)
            }
            Spacer(Modifier.height(12.dp))
        }
        if (selected != null) SendPanel(selected, dropping, act) else EmptyPanel(dropping, ui, act)
        ui.message?.let {
            Spacer(Modifier.height(8.dp))
            NCaps("[$it]", color = N.secondary)
        }
        Spacer(Modifier.height(22.dp))
        ActivityList(ui, act, Modifier.weight(1f))
    }
}

@Composable
private fun DropZone(active: Boolean, height: Int, onClick: () -> Unit, content: @Composable () -> Unit) {
    val line = if (active) N.display else N.borderVisible
    Box(
        Modifier.fillMaxWidth().height(height.dp).nCard(28.dp, color = if (active) N.surfaceRaised else N.surface)
            .nClickable(onClick = onClick)
            .drawBehind {
                drawRoundRect(
                    line, cornerRadius = CornerRadius(28.dp.toPx()),
                    style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 6.dp.toPx()), 0f), cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    topLeft = Offset(0.6.dp.toPx(), 0.6.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(size.width - 1.2.dp.toPx(), size.height - 1.2.dp.toPx()),
                )
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun EmptyPanel(dropping: Boolean, ui: UiState, act: Actions) {
    DropZone(dropping, 230, act::pickFiles) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ShapesArt(Modifier.size(width = 300.dp, height = 78.dp))
            NText(
                if (ui.peers.isEmpty()) tr("Waiting for a device", "Ждём устройство") else tr("Select a device to send to", "Выберите устройство"),
                style = NType.headline,
            )
            NMeta(tr("Drop files anywhere in this window, or click to browse", "Перетащите файлы в окно или нажмите, чтобы выбрать"))
        }
    }
}

@Composable
private fun SendPanel(p: PeerInfo, dropping: Boolean, act: Actions) {
    val ready = p.state == LinkState.READY || p.state == LinkState.SEEN || p.state == LinkState.PAIRING || p.state == LinkState.CONNECTING
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            NCaps(tr("Send to", "Отправить на"))
            NText(p.name, style = NType.headline, maxLines = 1)
        }
        if (p.trusted) NButton(tr("Forget", "Забыть"), { act.unpair(p.id) }, kind = BtnKind.GHOST, icon = Ic.TRASH)
    }
    Spacer(Modifier.height(14.dp))
    DropZone(dropping, 150, act::pickFiles) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            NText(if (dropping) tr("Release to send", "Отпустите, чтобы отправить") else tr("Drop files here or click", "Перетащите файлы или нажмите"), style = NType.headline)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NButton(tr("Files", "Файлы"), act::pickFiles, kind = BtnKind.FILLED, icon = Ic.FILE, enabled = ready)
                NButton(tr("Folder", "Папка"), act::pickFolder, icon = Ic.FOLDER, enabled = ready)
                NButton(tr("Clipboard", "Буфер"), act::sendClipboard, icon = Ic.CLIPBOARD, enabled = ready)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Composer(ready, act)
}

@Composable
private fun Composer(enabled: Boolean, act: Actions) {
    var text by remember { mutableStateOf("") }
    fun send() { val t = text.trim(); if (t.isNotEmpty()) { act.sendText(t); text = "" } }
    Row(
        Modifier.fillMaxWidth().nCard(50.dp, outlined = true).padding(start = 22.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) NText(tr("Type a message or paste a link", "Напишите сообщение или вставьте ссылку"), style = NType.body, color = N.disabled, maxLines = 1)
            BasicTextField(
                text, { text = it }, Modifier.fillMaxWidth().onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && !it.isShiftPressed) { send(); true } else false
                },
                textStyle = NType.body.copy(color = N.display), cursorBrush = SolidColor(N.display), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
            )
        }
        NIconButton(Ic.SEND, ::send, tint = if (text.isBlank() || !enabled) N.disabled else N.display)
    }
}

// ---- activity ----------------------------------------------------------------------------------

private sealed interface Entry {
    val time: Long
    data class T(val t: TransferInfo) : Entry { override val time get() = t.startedAt }
    data class X(val x: TextItem) : Entry { override val time get() = x.time }
}

@Composable
private fun ActivityList(ui: UiState, act: Actions, modifier: Modifier) {
    val entries = (ui.transfers.map { Entry.T(it) } + ui.texts.map { Entry.X(it) }).sortedByDescending { it.time }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NCaps(tr("Activity", "Активность"))
            Spacer(Modifier.weight(1f))
            if (ui.transfers.any { it.finished }) NButton(tr("Clear", "Очистить"), act::clearFinished, kind = BtnKind.GHOST)
            NButton(tr("Open folder", "Папка"), act::openSaveDir, kind = BtnKind.GHOST, icon = Ic.FOLDER)
        }
        Spacer(Modifier.height(8.dp))
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(bottom = 30.dp), contentAlignment = Alignment.Center) {
                NCaps(tr("Nothing here yet", "Пока пусто"), color = N.disabled)
            }
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { if (it is Entry.T) "t${it.t.direction}${it.t.id}" else "x${(it as Entry.X).x.id}" }) { e ->
                when (e) {
                    is Entry.T -> TransferRow(e.t, act)
                    is Entry.X -> TextRow(e.x, act)
                }
            }
        }
    }
}

@Composable
private fun TransferRow(t: TransferInfo, act: Actions) {
    val incoming = t.direction == Direction.IN
    val failed = t.state == TransferState.FAILED
    Column(
        Modifier.fillMaxWidth().nCard(24.dp, outlined = t.needsAnswer).then(if (t.needsAnswer) Modifier.border(1.dp, N.accent, RoundedCornerShape(24.dp)) else Modifier)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            NIcon(if (incoming) Ic.DOWN else Ic.UP, tint = if (t.needsAnswer) N.accent else N.secondary, size = 22.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (t.state == TransferState.ACTIVE) PulseDot()
                    NText(t.name, Modifier.weight(1f, fill = false), NType.bodyMedium, maxLines = 1)
                }
                val who = if (incoming) tr("from ", "от ") + t.peerName else tr("to ", "для ") + t.peerName
                val tail = when (t.state) {
                    TransferState.ACTIVE -> if (t.speed > 0) " · ${formatSpeed(t.speed)}" else ""
                    else -> ""
                }
                NMeta("$who · ${formatSize(t.size)}$tail")
            }
            when {
                t.needsAnswer -> {
                    NButton(tr("Decline", "Нет"), { act.accept(t.id, false) }, kind = BtnKind.OUTLINED)
                    NButton(tr("Accept", "Принять"), { act.accept(t.id, true) }, kind = BtnKind.FILLED)
                }
                t.state == TransferState.ACTIVE || t.state == TransferState.OFFERED -> NIconButton(Ic.CLOSE, { act.cancel(t.id) }, tint = N.secondary, size = 32.dp)
                t.state == TransferState.DONE && incoming && t.result != null -> {
                    NButton(tr("Open", "Открыть"), { act.open(t.result!!) }, kind = BtnKind.GHOST, icon = Ic.OPEN)
                    NButton(tr("In folder", "В папке"), { act.reveal(t.result!!) }, kind = BtnKind.GHOST, icon = Ic.FOLDER)
                }
                else -> NCaps(
                    when (t.state) {
                        TransferState.DONE -> tr("Sent", "Готово")
                        TransferState.FAILED -> tr("Failed", "Ошибка")
                        TransferState.CANCELED -> tr("Canceled", "Отменено")
                        else -> tr("Declined", "Отклонено")
                    },
                    color = if (failed) N.accent else N.secondary,
                )
            }
        }
        if (t.state == TransferState.ACTIVE || (t.state == TransferState.DONE && t.size > 0 && false)) DotBar(t.fraction)
        if (failed && t.error != null) NMeta(t.error!!, color = N.accent, maxLines = 2)
    }
}

@Composable
private fun TextRow(x: TextItem, act: Actions) {
    Row(
        Modifier.fillMaxWidth().nCard(24.dp).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        NIcon(if (x.clipboard) Ic.CLIPBOARD else Ic.TEXT, tint = N.secondary, size = 22.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            NText(x.text, style = NType.body, maxLines = 4)
            NMeta((if (x.incoming) tr("from ", "от ") else tr("to ", "для ")) + x.peerName + " · " + formatTime(x.time))
        }
        NIconButton(Ic.CLIPBOARD, { act.copy(x.text) }, tint = N.secondary, size = 32.dp)
    }
}

// ---- pairing -----------------------------------------------------------------------------------

@Composable
private fun PairDialog(req: PairingRequest, act: Actions) {
    Box(Modifier.fillMaxSize().background(N.bg.copy(alpha = 0.9f)).nClickable(onClick = {}), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(440.dp).nCard(28.dp, outlined = true).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NCaps(tr("Pair with", "Сопряжение с") + " " + req.peerName)
            NText(req.code.chunked(3).joinToString(" "), style = NType.dotDisplay)
            NText(
                tr("Check that the same code is shown on ${req.peerName}. Only continue if both match.", "Проверьте, что такой же код показан на устройстве ${req.peerName}. Продолжайте, только если коды совпадают."),
                style = NType.quote, color = N.secondary, align = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NButton(tr("Cancel", "Отмена"), { act.pair(req.peerId, false) }, kind = BtnKind.OUTLINED)
                NButton(tr("Codes match", "Коды совпадают"), { act.pair(req.peerId, true) }, kind = BtnKind.FILLED, icon = Ic.CHECK)
            }
        }
    }
}

// ---- settings ----------------------------------------------------------------------------------

@Composable
private fun SettingsPane(ui: UiState, act: Actions) {
    var name by remember(ui.deviceName) { mutableStateOf(ui.deviceName) }
    var ip by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NText(tr("Settings", "Настройки"), Modifier.weight(1f), NType.title.copy(fontSize = 44.sp, lineHeight = 48.sp))
                NIconButton(Ic.CLOSE, act::toggleSettings)
            }
            Spacer(Modifier.height(8.dp))
        }
        item { SectionCaps(tr("This device", "Это устройство")) }
        item {
            Row(
                Modifier.fillMaxWidth().nCard(24.dp).padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    NText(tr("Device name", "Имя устройства"), style = NType.bodyMedium)
                    NMeta(tr("Shown to other devices", "Так вас видят другие устройства"))
                }
                Box(Modifier.width(200.dp).nCard(50.dp, N.surfaceRaised).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    BasicTextField(name, { name = it.take(40) }, textStyle = NType.body.copy(color = N.display), cursorBrush = SolidColor(N.display), singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                NButton(tr("Save", "Сохранить"), { act.rename(name) }, kind = BtnKind.OUTLINED, enabled = name.isNotBlank() && name != ui.deviceName)
            }
        }
        item { SectionCaps(tr("Appearance", "Оформление")) }
        item {
            Row(Modifier.fillMaxWidth().nCard(50.dp).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(ThemeMode.SYSTEM to tr("System", "Система"), ThemeMode.LIGHT to tr("Light", "Светлая"), ThemeMode.DARK to tr("Dark", "Тёмная")).forEach { (m, label) ->
                    NButton(label, { act.setTheme(m) }, Modifier.weight(1f), kind = if (ThemeState.mode == m) BtnKind.DARK else BtnKind.GHOST)
                }
            }
        }
        item { SectionCaps(tr("Language", "Язык")) }
        item {
            Row(Modifier.fillMaxWidth().nCard(50.dp).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("" to tr("System", "Система"), "ru" to "Русский", "en" to "English").forEach { (code, label) ->
                    NButton(label, { act.setLanguage(code) }, Modifier.weight(1f), kind = if (ui.language == code) BtnKind.DARK else BtnKind.GHOST)
                }
            }
        }
        item { SectionCaps(tr("Receiving", "Приём")) }
        item { SwitchRow(tr("Accept from paired devices automatically", "Принимать от своих устройств автоматически"), tr("Unknown devices always ask first", "Незнакомые устройства всегда спрашивают"), ui.settings.autoAccept, act::setAutoAccept) }
        item {
            Row(
                Modifier.fillMaxWidth().nCard(24.dp).padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    NText(tr("Save folder", "Папка для файлов"), style = NType.bodyMedium)
                    NMeta(ui.saveDir)
                }
                NButton(tr("Open", "Открыть"), act::openSaveDir, kind = BtnKind.GHOST)
                NButton(tr("Change", "Изменить"), act::changeSaveDir, kind = BtnKind.OUTLINED)
            }
        }
        item { SectionCaps(tr("Sync", "Синхронизация")) }
        item { SwitchRow(tr("Share clipboard text with paired devices", "Делиться текстом из буфера обмена"), tr("Whatever you copy appears on the other device", "Скопированное здесь появится на другом устройстве"), ui.settings.clipboardSync, act::setClipboardSync) }
        item { SectionCaps(tr("Windows", "Windows")) }
        item { SwitchRow(tr("Keep running in the tray when closed", "Оставаться в трее при закрытии"), tr("Needed to receive files in the background", "Нужно, чтобы принимать файлы в фоне"), ui.settings.minimizeToTray, act::setMinimizeToTray) }
        item { SwitchRow(tr("Start with Windows", "Запускать вместе с Windows"), if (ui.settings.canIntegrate) tr("Starts minimized in the tray", "Запускается свёрнутым в трей") else tr("Only in the installed app", "Только в установленном приложении"), ui.settings.autostart, act::setAutostart, enabled = ui.settings.canIntegrate) }
        item { SwitchRow(tr("“Send via Essential Share” in the right-click menu", "«Отправить через Essential Share» в меню правой кнопки"), if (ui.settings.canIntegrate) tr("Windows 11: under “Show more options” (or Shift+right-click)", "Windows 11: в «Показать дополнительные параметры» (или Shift + правая кнопка)") else tr("Only in the installed app", "Только в установленном приложении"), ui.settings.sendTo, act::setSendTo, enabled = ui.settings.canIntegrate) }
        item { SectionCaps(tr("Network", "Сеть")) }
        item {
            Row(
                Modifier.fillMaxWidth().nCard(24.dp).padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    NText(tr("Connect by address", "Подключиться по адресу"), style = NType.bodyMedium)
                    NMeta(tr("If the device is not found automatically: ip:port", "Если устройство не находится само: ip:порт"))
                }
                Box(Modifier.width(200.dp).nCard(50.dp, N.surfaceRaised).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    if (ip.isEmpty()) NText("192.168.0.12:40000", style = NType.body, color = N.disabled)
                    BasicTextField(ip, { ip = it.trim() }, textStyle = NType.body.copy(color = N.display), cursorBrush = SolidColor(N.display), singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                NButton(tr("Connect", "Связь"), { act.connectIp(ip) }, kind = BtnKind.OUTLINED, enabled = ip.contains(':'))
            }
        }
        item {
            NText(
                tr("Unofficial fan-made app. Not affiliated with, endorsed by or sponsored by Nothing Technology.", "Неофициальное приложение, сделанное энтузиастом. Не связано с Nothing Technology и не одобрено ею."),
                Modifier.padding(start = 4.dp, top = 22.dp, bottom = 26.dp), NType.label, N.secondary, maxLines = 3,
            )
        }
    }
}

@Composable
private fun SectionCaps(text: String) {
    NCaps(text, Modifier.padding(top = 14.dp, start = 4.dp))
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().nCard(24.dp).padding(horizontal = 18.dp, vertical = 14.dp).alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            NText(title, style = NType.bodyMedium, maxLines = 2)
            NMeta(sub, maxLines = 2)
        }
        NSwitch(checked) { if (enabled) onChange(it) }
    }
}
