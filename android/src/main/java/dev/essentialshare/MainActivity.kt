package dev.essentialshare

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import dev.essentialshare.ui.ThemeMode
import dev.essentialshare.ui.ThemeState
import dev.essentialshare.ui.Logotype
import dev.essentialshare.ui.ShapesArt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.essentialshare.core.LinkState
import dev.essentialshare.core.PeerInfo
import dev.essentialshare.core.TextItem
import dev.essentialshare.core.TransferInfo
import dev.essentialshare.ui.BtnKind
import dev.essentialshare.ui.DeviceTile
import dev.essentialshare.ui.Gap
import dev.essentialshare.ui.Ic
import dev.essentialshare.ui.N
import dev.essentialshare.ui.NButton
import dev.essentialshare.ui.NCaps
import dev.essentialshare.ui.NIcon
import dev.essentialshare.ui.NIconButton
import dev.essentialshare.ui.NMeta
import dev.essentialshare.ui.NText
import dev.essentialshare.ui.NType
import dev.essentialshare.ui.PairDialog
import dev.essentialshare.ui.ScanPanel
import dev.essentialshare.ui.RadarDot
import dev.essentialshare.ui.SectionCaps
import dev.essentialshare.ui.SwitchRow
import dev.essentialshare.ui.TextRow
import dev.essentialshare.ui.TransferRow
import dev.essentialshare.ui.nCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

@Composable
fun EssentialTheme(content: @Composable () -> Unit) {
    ThemeState.systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val light = ThemeState.light
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val w = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        val c = androidx.core.view.WindowCompat.getInsetsController(w, view)
        c.isAppearanceLightStatusBars = light
        c.isAppearanceLightNavigationBars = light
    }
    val scheme = if (light) lightColorScheme(background = N.bg, surface = N.bg, onSurface = N.primary, primary = N.display)
    else darkColorScheme(background = N.bg, surface = N.bg, onSurface = N.primary, primary = N.display)
    MaterialTheme(colorScheme = scheme, content = content)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { EssentialTheme { Root() } }
    }

    override fun onStart() { super.onStart(); Rt.uiStarted() }
    override fun onStop() { Rt.uiStopped(); super.onStop() }
}

private enum class Screen { HOME, SETTINGS }

fun localAddress(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
}.getOrNull()

@Composable
private fun Root() {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf(Screen.HOME) }
    var sheetId by remember { mutableStateOf<String?>(null) }
    val pairing by Rt.node.pairing.collectAsState()

    val perms = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
        }.toTypedArray()
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Rt.firstRunDone = true
        Rt.ensureService()
    }
    LaunchedEffect(Unit) {
        if (!Rt.firstRunDone && perms.isNotEmpty()) permLauncher.launch(perms) else Rt.ensureService()
    }

    BackHandler(enabled = sheetId != null || screen != Screen.HOME) {
        if (sheetId != null) sheetId = null else screen = Screen.HOME
    }

    Box(Modifier.fillMaxSize().background(N.bg)) {
        when (screen) {
            Screen.HOME -> HomeScreen(onSettings = { screen = Screen.SETTINGS }, onDevice = { p ->
                if (p.state == LinkState.SEEN && !p.trusted) Rt.node.connectPeer(p.id)
                sheetId = p.id
            }, selectedId = sheetId)
            Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.HOME })
        }
        val peers by Rt.node.peers.collectAsState()
        val sheetPeer = peers.firstOrNull { it.id == sheetId }
        AnimatedVisibility(sheetId != null && screen == Screen.HOME, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { sheetId = null })
        }
        AnimatedVisibility(
            sheetPeer != null && screen == Screen.HOME, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it }, exit = slideOutVertically { it },
        ) {
            sheetPeer?.let { SendSheet(it) { sheetId = null } }
        }
        pairing.firstOrNull()?.let { PairDialog(it) { ok -> Rt.node.answerPairing(it.peerId, ok) } }
    }
}

// ---- home ----------------------------------------------------------------------------------------

private sealed interface Entry {
    val time: Long
    data class T(val t: TransferInfo) : Entry { override val time get() = t.startedAt }
    data class X(val x: TextItem) : Entry { override val time get() = x.time }
}

@Composable
private fun HomeScreen(onSettings: () -> Unit, onDevice: (PeerInfo) -> Unit, selectedId: String?) {
    val ctx = LocalContext.current
    val peers by Rt.node.peers.collectAsState()
    val transfers by Rt.node.transfers.collectAsState()
    val texts by Rt.node.texts.collectAsState()
    val entries = (transfers.map { Entry.T(it) } + texts.map { Entry.X(it) }).sortedByDescending { it.time }
    val online = peers.filter { it.state != LinkState.OFFLINE }
    val connected = peers.count { it.state == LinkState.READY }

    LazyColumn(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Logotype(Modifier.padding(top = 14.dp))
                    NText(tr("Share", "Обмен"), Modifier.padding(top = 6.dp), NType.title)
                }
                NIconButton(Ic.GEAR, onSettings, tint = N.secondary, size = 48.dp)
            }
        }
        item {
            NCaps(Rt.deviceName + " · " + if (connected > 0) tr("Connected", "Подключено") else tr("Ready", "Готов"), color = N.secondary)
            Gap(4)
        }
        item {
            ScanPanel(
                online.map { RadarDot(it.id, it.state == LinkState.READY, it.id == selectedId, it.platform == dev.essentialshare.core.Platform.ANDROID) },
                tr("Scanning", "Поиск"), Modifier.fillMaxWidth().height(144.dp), selfPhone = true,
            )
        }
        item {
            Row(Modifier.padding(top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                NCaps(tr("Nearby", "Рядом"))
                Gapw8()
                NCaps(online.size.toString(), color = N.display)
            }
        }
        if (peers.isEmpty()) item {
            Column(Modifier.fillMaxWidth().nCard(24.dp).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ShapesArt(Modifier.fillMaxWidth().height(72.dp))
                NText(tr("Looking for devices…", "Ищем устройства…"), style = NType.bodyMedium)
                NMeta(
                    tr("Open Essential Share on your PC. Both devices must be on the same Wi-Fi.", "Откройте Essential Share на компьютере. Оба устройства должны быть в одной сети Wi-Fi."),
                    maxLines = 4,
                )
            }
        }
        items(peers.chunked(2), key = { row -> row.joinToString { it.id } }) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { p -> DeviceTile(p, p.id == selectedId, Modifier.weight(1f)) { onDevice(p) } }
                if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            }
        }

        item {
            Row(Modifier.padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                NCaps(tr("Activity", "Активность"), Modifier.weight(1f))
                if (transfers.any { it.finished }) NButton(tr("Clear", "Очистить"), { Rt.node.clearFinished() }, kind = BtnKind.GHOST)
            }
        }
        if (entries.isEmpty()) item {
            Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                NCaps(tr("Nothing here yet", "Пока пусто"), color = N.disabled)
            }
        }
        items(entries, key = { if (it is Entry.T) "t${it.t.direction}${it.t.id}" else "x${(it as Entry.X).x.id}" }) { e ->
            when (e) {
                is Entry.T -> TransferRow(
                    e.t, onAnswer = { Rt.node.answerOffer(e.t.id, it) }, onCancel = { Rt.node.cancel(e.t.id) },
                    onOpen = { openFile(ctx, e.t.result) },
                    onFolder = { openFolder(ctx) },
                )
                is Entry.X -> TextRow(e.x) { copyText(ctx, e.x.text) }
            }
        }
    }
}

@Composable
private fun Gapw8() = androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))

internal fun openFile(ctx: Context, result: String?) {
    val uri = result?.let(Uri::parse) ?: return
    val mime = ctx.contentResolver.getType(uri)
    if (mime == "application/vnd.android.package-archive") {
        // the installer ignores apps that are not allowed to install packages: send the user to that switch first
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            android.widget.Toast.makeText(ctx, tr("Allow Essential Share to install apps, then open the file again", "Разрешите Essential Share устанавливать приложения и откройте файл снова"), android.widget.Toast.LENGTH_LONG).show()
            runCatching {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        val install = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(install) }.isSuccess) return
    }
    fun view(type: String) = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    // the exact type first; if nothing handles it, offer every app that accepts any file; last resort: the folder
    val opened = runCatching { ctx.startActivity(view(mime ?: "*/*")) }.isSuccess ||
        runCatching { ctx.startActivity(Intent.createChooser(view("*/*"), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    if (!opened) {
        android.widget.Toast.makeText(ctx, tr("No app can open this file, showing the folder", "Нет приложения для этого файла, открываю папку"), android.widget.Toast.LENGTH_SHORT).show()
        openFolder(ctx)
    }
}

/** Opens the Essential Share folder in the file manager (falls back to the Downloads list). */
private fun openFolder(ctx: Context) {
    val folder = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FEssential%20Share")
    val direct = Intent(Intent.ACTION_VIEW).setDataAndType(folder, "vnd.android.document/directory").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val ok = runCatching { ctx.startActivity(direct) }.isSuccess
    if (!ok) runCatching { ctx.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun copyText(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Essential Share", text))
}

// ---- send sheet ----------------------------------------------------------------------------------

@Composable
private fun ActionTile(ic: Ic, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(92.dp).nCard(24.dp, N.surfaceRaised).clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        NIcon(ic, size = 24.dp)
        NText(label, style = NType.bodyMedium, maxLines = 1)
    }
}

@Composable
private fun SendSheet(peer: PeerInfo, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var typing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }

    fun send(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val files = uris.mapNotNull { UriFile.from(ctx, it) }
            if (files.isEmpty()) return@launch
            Rt.node.sendFiles(peer.id, files)
        }
        onDismiss()
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { send(it) }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { send(it) }

    fun sendText() {
        val t = text.trim()
        if (t.isNotEmpty()) { Rt.node.sendText(peer.id, t); text = ""; onDismiss() }
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(N.surface)
            .navigationBarsPadding().imePadding().padding(horizontal = 20.dp).padding(top = 22.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                NCaps(tr("Send to", "Отправить на") + " · " + dev.essentialshare.ui.stateLabel(peer))
                NText(peer.name, style = NType.headline, maxLines = 1)
            }
            NIconButton(Ic.CLOSE, onDismiss, tint = N.secondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(Ic.FILE, tr("Files", "Файлы"), Modifier.weight(1f)) { pickFiles.launch(arrayOf("*/*")) }
            ActionTile(Ic.IMAGE, tr("Photos", "Фото и видео"), Modifier.weight(1f)) {
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(Ic.TEXT, tr("Text", "Текст"), Modifier.weight(1f)) { typing = !typing }
            ActionTile(Ic.CLIPBOARD, tr("Clipboard", "Буфер"), Modifier.weight(1f)) {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                if (t.isNullOrBlank()) note = tr("Clipboard has no text", "В буфере нет текста")
                else { Rt.node.sendText(peer.id, t, clipboard = true); onDismiss() }
            }
        }
        if (typing) {
            Row(
                Modifier.fillMaxWidth().nCard(50.dp, N.surfaceRaised).padding(start = 22.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (text.isEmpty()) NText(tr("Message or link", "Сообщение или ссылка"), style = NType.body, color = N.disabled, maxLines = 1)
                    BasicTextField(
                        text, { text = it }, Modifier.fillMaxWidth(), textStyle = NType.body.copy(color = N.display),
                        cursorBrush = SolidColor(N.display), singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { sendText() }),
                    )
                }
                NIconButton(Ic.SEND, ::sendText, tint = if (text.isBlank()) N.disabled else N.display, size = 44.dp)
            }
        }
        note?.let { NCaps("[$it]") }
    }
}

// ---- settings ------------------------------------------------------------------------------------

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val peers by Rt.node.peers.collectAsState()
    var name by remember { mutableStateOf(Rt.deviceName) }
    var autoAccept by remember { mutableStateOf(Rt.autoAccept) }
    var always by remember { mutableStateOf(Rt.alwaysReady) }
    var applyClip by remember { mutableStateOf(Rt.applyClipboard) }
    var ip by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val lang = remember { Lang.current(ctx) }
    val paired = peers.filter { it.trusted }
    val myAddr = remember { localAddress()?.let { "$it:${Rt.node.port}" } }

    LazyColumn(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NIconButton(Ic.BACK, onBack, size = 48.dp)
                NText(tr("Settings", "Настройки"), Modifier.padding(start = 6.dp), NType.title.copy(fontSize = 36.sp, lineHeight = 40.sp))
            }
        }
        item { SectionCaps(tr("This phone", "Этот телефон")) }
        item {
            Column(Modifier.fillMaxWidth().nCard(24.dp).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NText(tr("Device name", "Имя устройства"), style = NType.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f).nCard(50.dp, N.surfaceRaised).padding(horizontal = 18.dp, vertical = 12.dp)) {
                        BasicTextField(name, { name = it.take(40) }, textStyle = NType.body.copy(color = N.display), cursorBrush = SolidColor(N.display), singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    NButton(tr("Save", "Сохранить"), { Rt.deviceName = name; name = Rt.deviceName }, enabled = name.isNotBlank() && name != Rt.deviceName)
                }
                myAddr?.let { NMeta(tr("Address on this network: ", "Адрес в этой сети: ") + it) }
            }
        }
        item { SectionCaps(tr("Appearance", "Оформление")) }
        item {
            var mode by remember { mutableStateOf(Rt.themeMode) }
            Row(Modifier.fillMaxWidth().nCard(50.dp).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(ThemeMode.SYSTEM to tr("System", "Система"), ThemeMode.LIGHT to tr("Light", "Светлая"), ThemeMode.DARK to tr("Dark", "Тёмная")).forEach { (m, label) ->
                    NButton(label, { Rt.themeMode = m; mode = m }, Modifier.weight(1f), kind = if (mode == m) BtnKind.DARK else BtnKind.GHOST)
                }
            }
        }
        item { SectionCaps(tr("Receiving", "Приём")) }
        item {
            SwitchRow(
                tr("Always ready to receive", "Всегда готов к приёму"),
                tr("Keeps a quiet notification so the PC can find this phone with the screen off", "Тихое уведомление, чтобы ПК находил телефон при выключенном экране"),
                always,
            ) { always = it; Rt.alwaysReady = it }
        }
        item {
            SwitchRow(
                tr("Accept from paired devices automatically", "Принимать от своих устройств автоматически"),
                tr("Unknown devices always ask first", "Незнакомые устройства всегда спрашивают"), autoAccept,
            ) { autoAccept = it; Rt.autoAccept = it }
        }
        item {
            SwitchRow(
                tr("Put text from the PC clipboard on this phone", "Класть текст из буфера ПК в буфер телефона"),
                tr("Only for what you send as “Clipboard”", "Только то, что отправлено как «Буфер»"), applyClip,
            ) { applyClip = it; Rt.applyClipboard = it }
        }
        item {
            Row(Modifier.fillMaxWidth().nCard(24.dp).padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    NText(tr("Received files", "Полученные файлы"), style = NType.bodyMedium)
                    NMeta("Downloads / Essential Share")
                }
                NButton(tr("Open", "Открыть"), { openFolder(ctx) })
            }
        }
        item {
            val pm = Rt.powerManager(ctx)
            val ignoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
            Row(Modifier.fillMaxWidth().nCard(24.dp).padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    NText(tr("Background activity", "Работа в фоне"), style = NType.bodyMedium)
                    NMeta(if (ignoring) tr("Unrestricted", "Без ограничений") else tr("Allow it so transfers are not paused", "Разрешите, чтобы передачи не прерывались"), maxLines = 2)
                }
                if (!ignoring) NButton(tr("Allow", "Разрешить"), {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                })
            }
        }
        item { SectionCaps(tr("Language", "Язык")) }
        item {
            Row(Modifier.fillMaxWidth().nCard(50.dp).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("" to tr("System", "Система"), "ru" to "Русский", "en" to "English").forEach { (code, label) ->
                    NButton(label, { Lang.set(ctx, code) }, Modifier.weight(1f), kind = if (lang == code) BtnKind.FILLED else BtnKind.GHOST)
                }
            }
        }
        if (paired.isNotEmpty()) {
            item { SectionCaps(tr("Paired devices", "Сопряжённые устройства")) }
            items(paired, key = { "p" + it.id }) { p ->
                Row(Modifier.fillMaxWidth().nCard(24.dp).padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        NText(p.name, style = NType.bodyMedium, maxLines = 1)
                        NCaps(dev.essentialshare.ui.stateLabel(p))
                    }
                    NButton(tr("Forget", "Забыть"), { Rt.node.unpair(p.id) }, kind = BtnKind.GHOST, icon = Ic.TRASH)
                }
            }
        }
        item { SectionCaps(tr("Network", "Сеть")) }
        item {
            Column(Modifier.fillMaxWidth().nCard(24.dp).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NText(tr("Connect by address", "Подключиться по адресу"), style = NType.bodyMedium)
                NMeta(tr("If the PC is not found automatically (ip:port from its Settings)", "Если ПК не находится сам (ip:порт из его настроек)"), maxLines = 3)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f).nCard(50.dp, N.surfaceRaised).padding(horizontal = 18.dp, vertical = 12.dp)) {
                        if (ip.isEmpty()) NText("192.168.0.12:40000", style = NType.body, color = N.disabled)
                        BasicTextField(ip, { ip = it.trim() }, textStyle = NType.body.copy(color = N.display), cursorBrush = SolidColor(N.display), singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    NButton(tr("Connect", "Связь"), {
                        val i = ip.lastIndexOf(':')
                        val port = ip.substring(i + 1).toIntOrNull()
                        if (i <= 0 || port == null) note = tr("Use the form ip:port", "Формат: ip:порт")
                        else { note = tr("Connecting…", "Подключаемся…"); Rt.node.connectManually(ip.substring(0, i), port) { ok -> note = if (ok) tr("Connected", "Подключено") else tr("Could not connect", "Не удалось подключиться") } }
                    }, enabled = ip.contains(':'))
                }
                note?.let { NCaps("[$it]") }
            }
        }
        item {
            NText(
                tr("Unofficial fan-made app. Not affiliated with, endorsed by or sponsored by Nothing Technology.", "Неофициальное приложение, сделанное энтузиастом. Не связано с Nothing Technology и не одобрено ею."),
                Modifier.padding(start = 4.dp, top = 22.dp), NType.label, N.secondary, maxLines = 4,
            )
        }
    }
}
