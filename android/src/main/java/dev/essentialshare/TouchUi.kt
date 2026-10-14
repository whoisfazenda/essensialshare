package dev.essentialshare

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.essentialshare.core.Crypto
import dev.essentialshare.core.Direction
import dev.essentialshare.core.LinkState
import dev.essentialshare.core.OutgoingFile
import dev.essentialshare.core.TransferState
import dev.essentialshare.ui.BtnKind
import dev.essentialshare.ui.DOT_ICONS
import dev.essentialshare.ui.DotBar
import dev.essentialshare.ui.Ic
import dev.essentialshare.ui.N
import dev.essentialshare.ui.NButton
import dev.essentialshare.ui.NCaps
import dev.essentialshare.ui.NIconButton
import dev.essentialshare.ui.NMeta
import dev.essentialshare.ui.NText
import dev.essentialshare.ui.NType
import dev.essentialshare.ui.glyphAt
import dev.essentialshare.ui.nCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private enum class Stage { SEARCHING, CONNECTING, CONNECTED, FAILED }

/**
 * Touch to share. Both phones open this screen and hold their backs together: Bluetooth LE notices that they are
 * touching, the phones connect over the shared Wi-Fi and the files go. The pairing dialog is drawn by the host
 * screen, not here.
 *
 * [passive] is the receiving side that was not looking for anything: the app was closed, the other phone touched
 * it and [attachPeer] says which one. It shows the same animation (as a window over whatever is on screen), does
 * not scan, and closes itself when the transfer is over.
 */
@Composable
fun TouchScreen(
    initialFiles: List<OutgoingFile>, text: String?, canPick: Boolean,
    passive: Boolean = false, attachPeer: String? = null, awaitLink: Boolean = false, onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val radar = remember { TouchRadar(ctx, Rt.scope) }
    val openedAt = remember { System.currentTimeMillis() }

    var granted by remember { mutableStateOf(passive || TouchRadar.hasPermissions()) }
    var problem by remember { mutableStateOf(if (passive) null else TouchRadar.unavailable(ctx)) }
    // a window outside any activity has no result registry, and the passive side never asks for anything
    val permLauncher = if (passive) null else rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = TouchRadar.hasPermissions() }

    var files by remember { mutableStateOf(initialFiles) }
    var stage by remember { mutableStateOf(if (attachPeer != null) Stage.CONNECTING else Stage.SEARCHING) }
    var prefix by remember { mutableStateOf(attachPeer?.take(12)) }
    var peerId by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf(false) }
    // [awaitLink]: the link does not exist yet, a direct Wi-Fi link is being opened for it
    var connectDone by remember { mutableStateOf(attachPeer != null && !awaitLink) }
    var direct by remember { mutableStateOf(awaitLink) }
    var startMark by remember { mutableLongStateOf(if (attachPeer != null) openedAt - 5000 else openedAt) }
    var touchEpoch by remember { mutableIntStateOf(if (attachPeer != null) 1 else 0) }

    val nearby by radar.nearby.collectAsState()
    val peers by Rt.node.peers.collectAsState()
    val transfers by Rt.node.transfers.collectAsState()
    val pairing by Rt.node.pairing.collectAsState()

    // ---- bluetooth: permission, switched on, and the radar itself ------------------------------------
    LaunchedEffect(Unit) { if (!granted) permLauncher?.launch(TouchRadar.permissions) }
    LaunchedEffect(Unit) { if (!passive) while (true) { problem = TouchRadar.unavailable(ctx); delay(1000) } }
    LaunchedEffect(granted, problem) {
        if (passive) return@LaunchedEffect
        if (granted && problem == null) {
            while (Rt.node.port == 0) delay(100)
            radar.start()
        } else radar.stop()
    }
    DisposableEffect(Unit) { onDispose { radar.stop() } }
    // while a touch screen is in front the app shows the animation itself: no window over it. Only while it is in
    // front: an app that went to the background with this screen still open must not block the window.
    if (!passive) LifecycleResumeEffect(Unit) {
        TouchState.screens++
        onPauseOrDispose { TouchState.screens-- }
    }
    LaunchedEffect(Unit) { if (passive) touchHaptic(ctx) }
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }

    // ---- the touch ----------------------------------------------------------------------------------
    // no shared Wi-Fi: open a direct link here and ask the touched phone, over Bluetooth, to join it
    fun startDirect(n: Nearby) {
        if (stage != Stage.CONNECTING || peerId != null || direct) return
        direct = true
        val seed = ByteArray(8).also { Crypto.random.nextBytes(it) }
        Direct.host(ctx, Rt.node.selfId.take(12), seed) { ok ->
            if (!ok) { if (stage == Stage.CONNECTING) stage = Stage.FAILED }
            else radar.requestDirect(n.prefix, seed)
        }
    }

    fun begin(n: Nearby) {
        if (stage != Stage.SEARCHING) return
        prefix = n.prefix; startMark = System.currentTimeMillis() - 5000; connectDone = false; direct = false
        stage = Stage.CONNECTING; touchEpoch++
        touchHaptic(ctx)
        // the shared network first, and quickly: if the other phone is not on it, the direct link takes over
        Rt.node.connectNearby(n.prefix, n.host, n.port, timeoutMs = 1500) { ok ->
            // the other phone may be in a pocket with the app closed: tell it, so it can show its side of the animation
            if (ok) Rt.node.announceTouch(n.prefix)
            Rt.scope.launch { if (ok) connectDone = true else if (stage == Stage.CONNECTING && peerId == null) startDirect(n) }
        }
    }
    LaunchedEffect(radar) { radar.touch.collect { begin(it) } }

    // the other phone got there first: its connection (or its first file) arrives while we are still searching
    LaunchedEffect(pairing, transfers, nearby) {
        if (stage != Stage.SEARCHING) return@LaunchedEffect
        val near = nearby.map { it.prefix }
        val id = pairing.firstOrNull { p -> near.any { p.peerId.startsWith(it) } }?.peerId
            ?: transfers.firstOrNull { t -> t.direction == Direction.IN && t.startedAt >= openedAt && near.any { t.peerId.startsWith(it) } }?.peerId
        if (id != null) {
            prefix = id.take(12); startMark = openedAt; connectDone = true
            stage = Stage.CONNECTING; touchEpoch++
            touchHaptic(ctx)
        }
    }

    // connected: remember who, then send what was queued
    LaunchedEffect(peers, stage) {
        if (stage != Stage.CONNECTING) return@LaunchedEffect
        val p = prefix ?: return@LaunchedEffect
        peers.firstOrNull { it.id.startsWith(p) && it.state == LinkState.READY }?.let { peerId = it.id; stage = Stage.CONNECTED }
    }
    LaunchedEffect(stage, peerId) {
        val id = peerId ?: return@LaunchedEffect
        if (stage == Stage.CONNECTED && !sent) {
            sent = true
            radar.clearRequest()
            if (!passive) prefix?.let { Rt.node.announceTouch(it) }
            if (files.isNotEmpty()) Rt.node.sendFiles(id, files) else text?.let { Rt.node.sendText(id, it) }
        }
    }

    // waiting for the other side: give up when nothing is happening any more
    LaunchedEffect(stage) {
        if (stage != Stage.CONNECTING) return@LaunchedEffect
        var waited = 0
        var lost = 0
        while (stage == Stage.CONNECTING) {
            delay(500)
            val pending = Rt.node.pairing.value.isNotEmpty()
            val p = prefix
            val alive = p != null && Rt.node.peers.value.any { it.id.startsWith(p) && (it.state == LinkState.PAIRING || it.state == LinkState.READY) }
            if (!pending) waited += 500
            lost = if (connectDone && !alive && !pending) lost + 500 else 0
            if (waited >= (if (direct) 40_000 else 20_000) || lost >= 1500) stage = Stage.FAILED
        }
    }

    fun reset() {
        radar.clearRequest()
        stage = Stage.SEARCHING; prefix = null; peerId = null; sent = false; connectDone = false; direct = false
        startMark = System.currentTimeMillis()
    }

    // ---- what the transfer looks like -----------------------------------------------------------------
    val mine = transfers.filter { peerId != null && it.peerId == peerId && it.startedAt >= startMark }
    val active = mine.filter { !it.finished }
    val offers = mine.filter { it.needsAnswer }
    val okCount = mine.count { it.state == TransferState.DONE }
    val allDone = mine.isNotEmpty() && active.isEmpty()
    val showDone = allDone && okCount > 0
    val total = mine.sumOf { it.size }
    val progress = if (total > 0) (mine.sumOf { it.transferred }.toDouble() / total).toFloat().coerceIn(0f, 1f) else if (allDone) 1f else 0f
    val dir = (active.firstOrNull() ?: mine.firstOrNull())?.direction
    val peerName = peers.firstOrNull { it.id == peerId }?.name ?: tr("device", "устройство")

    // ---- animation drivers ---------------------------------------------------------------------------
    val gap = remember { Animatable(1f) }
    val burst = remember { Animatable(0f) }
    val doneAnim = remember { Animatable(0f) }
    val searching = stage == Stage.SEARCHING && granted && problem == null
    LaunchedEffect(searching) {
        if (searching) snapshotFlow { 1f - (nearby.maxOfOrNull { it.closeness } ?: 0f) }
            .collectLatest { gap.animateTo(it, spring(stiffness = Spring.StiffnessLow)) }
    }
    LaunchedEffect(touchEpoch) {
        if (touchEpoch == 0) return@LaunchedEffect
        launch { burst.snapTo(0f); burst.animateTo(1f, tween(1000, easing = LinearOutSlowInEasing)) }
        gap.animateTo(0f, tween(130, easing = FastOutSlowInEasing))
        delay(300)
        gap.animateTo(0.36f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow))
    }
    LaunchedEffect(showDone) {
        if (showDone) { doneHaptic(ctx); doneAnim.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 300f)) } else doneAnim.snapTo(0f)
    }

    // ---- picking more files ---------------------------------------------------------------------------
    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val list = uris.mapNotNull { UriFile.from(ctx, it) }
            if (list.isEmpty()) return@launch
            val id = peerId
            if (id != null && stage == Stage.CONNECTED) Rt.node.sendFiles(id, list) else files = files + list
        }
    }
    val pickFiles = if (canPick && !passive) rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { add(it) } else null
    val pickMedia = if (canPick && !passive) rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { add(it) } else null

    // the passive window is not something to dismiss by hand: it goes away when the transfer is over
    LaunchedEffect(showDone, stage) {
        if (!passive) return@LaunchedEffect
        if (showDone) { delay(3500); onClose() }
        else if (stage == Stage.FAILED) { delay(4000); onClose() }
    }

    // ---- screen -------------------------------------------------------------------------------------
    val title: String
    val sub: String
    when {
        !granted -> { title = tr("Bluetooth access needed", "Нужен доступ к Bluetooth"); sub = tr("It only finds the phone you touch. Files go over Wi-Fi.", "Он только находит телефон, который вы касаетесь. Файлы идут по Wi-Fi.") }
        problem == "off" -> { title = tr("Turn on Bluetooth", "Включите Bluetooth"); sub = tr("Touch to share needs it to feel the other phone.", "Он нужен, чтобы почувствовать второй телефон.") }
        problem != null -> { title = tr("No Bluetooth LE", "Нет Bluetooth LE"); sub = tr("This phone cannot do touch to share.", "Этот телефон не умеет делиться касанием.") }
        stage == Stage.FAILED -> { title = tr("Could not connect", "Не удалось соединиться"); sub = tr("Touch again. If it keeps failing, put both phones on the same Wi-Fi.", "Приложите ещё раз. Если не выходит, подключите оба телефона к одной Wi-Fi сети.") }
        stage == Stage.SEARCHING && nearby.isEmpty() -> { title = tr("Hold the phones together", "Приложите телефоны друг к другу"); sub = tr("Open “Touch” on the other phone too.", "На втором телефоне тоже откройте «Касанием».") }
        stage == Stage.SEARCHING -> { title = tr("Closer…", "Ещё ближе…"); sub = tr("Back to back, near the top.", "Спинкой к спинке, ближе к верху.") }
        stage == Stage.CONNECTING && direct -> { title = tr("Connecting…", "Соединяем…"); sub = tr("No shared Wi-Fi: opening a direct link. Allow it on the other phone if asked.", "Общей сети нет: открываем прямую связь. Если второй телефон спросит, разрешите.") }
        stage == Stage.CONNECTING -> { title = tr("Connecting…", "Соединяем…"); sub = tr("Confirm the code on both phones if asked.", "Если спросит, подтвердите код на обоих телефонах.") }
        showDone -> { title = tr("Done", "Готово"); sub = tr("Files: $okCount · $peerName", "Файлов: $okCount · $peerName") }
        active.isNotEmpty() && dir == Direction.IN -> { title = tr("Receiving", "Приём"); sub = active.first().name + " · " + peerName }
        active.isNotEmpty() -> { title = tr("Sending", "Отправка"); sub = active.first().name + " · " + peerName }
        else -> { title = tr("Connected", "Подключено"); sub = peerName + if (canPick) tr(" · pick what to send", " · выберите, что отправить") else "" }
    }

    Column(
        Modifier.fillMaxSize().background(N.bg).windowInsetsPadding(WindowInsets.statusBars).navigationBarsPadding().padding(horizontal = 20.dp),
    ) {
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            NIconButton(Ic.BACK, onClose, size = 48.dp)
            NCaps(tr("Touch to share", "Касанием"), Modifier.padding(start = 6.dp), color = N.secondary)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            TouchCanvas(
                gap = gap.value, burst = burst.value, done = doneAnim.value,
                peerNear = nearby.isNotEmpty() || stage != Stage.SEARCHING, ripples = searching,
                flowDir = if (active.isEmpty()) 0 else if (dir == Direction.OUT) 1 else -1,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NText(title, style = NType.headline, maxLines = 2)
            NMeta(sub, maxLines = 3)
            if (active.isNotEmpty()) DotBar(progress, Modifier.padding(top = 4.dp))

            offers.firstOrNull()?.let { o ->
                Row(Modifier.fillMaxWidth().nCard(24.dp).padding(start = 18.dp, top = 8.dp, bottom = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        NText(o.name, style = NType.bodyMedium, maxLines = 1)
                        NMeta(formatSize(o.size) + if (offers.size > 1) " · +${offers.size - 1}" else "")
                    }
                    NButton(tr("No", "Нет"), { Rt.node.answerOffer(o.id, false) }, kind = BtnKind.GHOST)
                    NButton(tr("Accept", "Принять"), { Rt.node.answerOffer(o.id, true) }, kind = BtnKind.FILLED)
                }
            }

            if (!granted) NButton(tr("Allow", "Разрешить"), { permLauncher?.launch(TouchRadar.permissions) }, kind = BtnKind.FILLED)
            else if (problem == "off") NButton("Bluetooth", {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, kind = BtnKind.FILLED)
            else if (stage == Stage.FAILED) NButton(tr("Try again", "Ещё раз"), ::reset, kind = BtnKind.FILLED)
            else if (showDone) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NButton(tr("Done", "Готово"), onClose, kind = BtnKind.DARK)
                NButton(tr("Open folder", "Открыть папку"), { openFolder(ctx) }, kind = BtnKind.GHOST)
            }

            if (canPick && granted && problem == null && stage != Stage.FAILED) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NButton(tr("Files", "Файлы"), { pickFiles?.launch(arrayOf("*/*")) }, icon = Ic.FILE)
                    NButton(tr("Photos", "Фото"), { pickMedia?.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }, icon = Ic.IMAGE)
                    if (files.isNotEmpty() && peerId == null) NMeta(tr("Queued: ${files.size}", "В очереди: ${files.size}"))
                }
            } else if (files.isNotEmpty() && peerId == null && stage != Stage.FAILED) NMeta(tr("Ready to send: ${files.size}", "К отправке: ${files.size}"))

            if (!passive && stage == Stage.SEARCHING) OverlayPermissionRow()

            // the fallback when a touch is not felt: tap the phone that is close
            if (stage == Stage.SEARCHING && searching) nearby.filter { it.closeness >= 0.2f }.take(2).forEach { n ->
                Row(
                    Modifier.fillMaxWidth().nCard(24.dp, N.surfaceRaised).clickable { begin(n) }.padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        NText(tr("Phone nearby", "Телефон рядом"), style = NType.bodyMedium)
                        NMeta(tr("Not felt? Tap to connect", "Не сработало? Нажмите, чтобы соединиться"))
                    }
                    NCaps("%d%%".format((n.closeness * 100).toInt()))
                }
            }
        }
    }
}

/**
 * Two dot-matrix phones. Far apart they drift towards each other as the signal grows; on the touch they meet,
 * rings of dots burst out and they settle with a little spring; while files move, packets of dots run between them
 * in the direction of the transfer; when it is done a yellow check pops in between.
 */
@Composable
private fun TouchCanvas(gap: Float, burst: Float, done: Float, peerNear: Boolean, ripples: Boolean, flowDir: Int, modifier: Modifier) {
    val t = rememberInfiniteTransition()
    val ripple by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart))
    val flow by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart))
    val blink by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse))
    val rippleAlpha by animateFloatAsState(if (ripples) 1f else 0f, tween(400))
    val peerColor by animateColorAsState(if (peerNear) N.ink else N.secondary, tween(250))
    val phone = DOT_ICONS["PHONE"]!!
    val check = DOT_ICONS["CHECK"]!!

    Canvas(modifier) {
        val gp = 7.dp.toPx()
        val gw = gp * 9
        val cx = size.width / 2
        val cy = size.height / 2
        val top = cy - gw / 2
        val bodyHalf = 2.5f * gp
        val nearOff = bodyHalf + gp * 0.5f
        val farOff = max(size.width * 0.34f, nearOff + gp * 3)
        val off = nearOff + (farOff - nearOff) * gap.coerceIn(0f, 1.2f)
        val bump = 1f + 0.22f * sin(PI.toFloat() * burst.coerceIn(0f, 1f))

        // waves of dots from the middle while looking for the other phone
        if (rippleAlpha > 0.01f) for (i in 0 until 3) {
            val p = (ripple + i / 3f) % 1f
            val r = gw * 0.45f + p * size.minDimension * 0.5f
            val n = max(14, (2 * PI * r / (gp * 1.15f)).toInt())
            val a = (1f - p) * 0.5f * rippleAlpha
            for (k in 0 until n) {
                val ang = 2 * PI * k / n
                drawCircle(N.ink.copy(alpha = a), (1.2f + 1.4f * (1f - p)).dp.toPx(), Offset(cx + (r * cos(ang)).toFloat(), cy + (r * sin(ang)).toFloat()))
            }
        }

        // the two phones
        glyphAt(phone, cx - off - gw / 2, top, gp, N.ink, 1f, gp * 0.38f * bump)
        val rightAlpha = if (peerNear) 1f else 0.14f + 0.22f * blink
        glyphAt(phone, cx + off - gw / 2, top, gp, peerColor, rightAlpha, gp * (if (peerNear) 0.38f * bump else 0.34f))

        // the burst at the moment of touch: three rings, the first one yellow
        if (burst > 0f && burst < 1f) for (j in 0 until 3) {
            val p = ((burst - j * 0.12f) / 0.76f).coerceIn(0f, 1f)
            if (p <= 0f || p >= 1f) continue
            val r = p * size.minDimension * 0.62f
            val n = 26 + j * 8
            val color = if (j == 0) N.yellow else N.ink
            for (k in 0 until n) {
                val ang = 2 * PI * k / n + j * 0.2
                drawCircle(color.copy(alpha = (1f - p)), ((1f - p) * 3.4f + 0.8f).dp.toPx(), Offset(cx + (r * cos(ang)).toFloat(), cy + (r * sin(ang)).toFloat()))
            }
        }
        if (burst in 0.001f..0.35f) drawCircle(N.yellow.copy(alpha = 1f - burst / 0.35f), gp * (0.6f + burst * 5f), Offset(cx, cy))

        // packets running between the phones while bytes move
        if (flowDir != 0) {
            val x0 = cx - off + bodyHalf + gp
            val x1 = cx + off - bodyHalf - gp
            if (x1 - x0 > 4 * gp) {
                val n = ((x1 - x0) / 9.dp.toPx()).toInt().coerceAtLeast(3)
                val head = flow * (n + 8) - 4
                for (i in 0..n) {
                    val idx = if (flowDir > 0) i else n - i
                    val d = head - idx
                    val glow = if (d in 0f..7f) 1f - d / 7f else 0f
                    val lead = d in 0f..1f
                    drawCircle((if (lead) N.yellow else N.display).copy(alpha = 0.2f + 0.8f * glow), (1.7f + 2.1f * glow).dp.toPx(), Offset(x0 + i * (x1 - x0) / n, cy))
                }
            }
        }

        // done: a yellow check pops between the phones
        if (done > 0.01f) {
            val p = gp * 0.8f * (0.6f + 0.4f * done)
            glyphAt(check, cx - 9 * p / 2, cy - 5 * p / 2, p, N.yellow, done.coerceIn(0f, 1f), p * 0.42f)
        }
    }
}
