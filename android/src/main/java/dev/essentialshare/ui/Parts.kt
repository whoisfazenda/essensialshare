package dev.essentialshare.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.essentialshare.core.Direction
import dev.essentialshare.core.LinkState
import dev.essentialshare.core.PairingRequest
import dev.essentialshare.core.PeerInfo
import dev.essentialshare.core.Platform
import dev.essentialshare.core.TextItem
import dev.essentialshare.core.TransferInfo
import dev.essentialshare.core.TransferState
import dev.essentialshare.formatSize
import dev.essentialshare.formatSpeed
import dev.essentialshare.formatTime
import dev.essentialshare.tr

fun stateLabel(p: PeerInfo): String = when (p.state) {
    LinkState.READY -> tr("Connected", "Подключено")
    LinkState.PAIRING -> tr("Pairing", "Сопряжение")
    LinkState.CONNECTING -> tr("Connecting", "Подключение")
    LinkState.SEEN -> if (p.trusted) tr("Online", "В сети") else tr("Tap to pair", "Нажмите для пары")
    LinkState.OFFLINE -> tr("Offline", "Не в сети")
}

@Composable
fun StatusDot(s: LinkState) {
    Canvas(Modifier.size(10.dp)) {
        when (s) {
            LinkState.READY -> drawCircle(N.display)
            LinkState.SEEN, LinkState.CONNECTING, LinkState.PAIRING -> drawCircle(N.secondary, style = Stroke(1.5.dp.toPx()))
            LinkState.OFFLINE -> drawCircle(N.disabled.copy(alpha = 0.6f), radius = 2.dp.toPx())
        }
    }
}

@Composable
fun DeviceCard(p: PeerInfo, selected: Boolean, onClick: () -> Unit) {
    val off = p.state == LinkState.OFFLINE
    Row(
        Modifier.fillMaxWidth().nCard(24.dp, outlined = selected, borderColor = N.ink).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp),
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
fun DeviceTile(p: PeerInfo, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val off = p.state == LinkState.OFFLINE
    Column(
        modifier.height(132.dp).nCard(28.dp, outlined = selected, borderColor = N.ink).clickable(onClick = onClick).padding(18.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NIcon(if (p.platform == Platform.ANDROID) Ic.PHONE else Ic.PC, tint = if (off) N.disabled else N.display, size = 28.dp)
            Spacer(Modifier.weight(1f))
            if (p.trusted) NIcon(Ic.SHIELD, tint = N.secondary, size = 16.dp)
            Spacer(Modifier.width(8.dp))
            StatusDot(p.state)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NText(p.name, style = NType.bodyMedium, color = if (off) N.secondary else N.display, maxLines = 2)
            NCaps(stateLabel(p), color = if (p.state == LinkState.READY) N.display else N.secondary)
        }
    }
}

@Composable
fun TransferRow(t: TransferInfo, onAnswer: (Boolean) -> Unit, onCancel: () -> Unit, onOpen: () -> Unit, onFolder: () -> Unit) {
    val incoming = t.direction == Direction.IN
    val failed = t.state == TransferState.FAILED
    Column(
        Modifier.fillMaxWidth().nCard(24.dp).then(if (t.needsAnswer) Modifier.border(1.dp, N.accent, RoundedCornerShape(24.dp)) else Modifier)
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
                val tail = if (t.state == TransferState.ACTIVE && t.speed > 0) " · ${formatSpeed(t.speed)}" else ""
                NMeta("$who · ${formatSize(t.size)}$tail")
            }
            when {
                t.state == TransferState.ACTIVE || (t.state == TransferState.OFFERED && !t.needsAnswer) -> NIconButton(Ic.CLOSE, onCancel, tint = N.secondary, size = 40.dp)
                t.state == TransferState.DONE && incoming && t.result != null -> NCaps(tr("Received", "Получено"))
                !t.needsAnswer -> NCaps(
                    when (t.state) {
                        TransferState.DONE -> tr("Sent", "Готово")
                        TransferState.FAILED -> tr("Failed", "Ошибка")
                        TransferState.CANCELED -> tr("Canceled", "Отмена")
                        else -> tr("Declined", "Отклонено")
                    },
                    color = if (failed) N.accent else N.secondary,
                )
            }
        }
        if (t.needsAnswer) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NButton(tr("Decline", "Отклонить"), { onAnswer(false) }, Modifier.weight(1f))
                NButton(tr("Accept", "Принять"), { onAnswer(true) }, Modifier.weight(1f), kind = BtnKind.FILLED)
            }
        }
        if (t.state == TransferState.DONE && incoming && t.result != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NButton(tr("Open", "Открыть"), onOpen, Modifier.weight(1f), icon = Ic.OPEN)
                NButton(tr("In folder", "В папке"), onFolder, Modifier.weight(1f), icon = Ic.FOLDER)
            }
        }
        if (t.state == TransferState.ACTIVE) DotBar(t.fraction, cells = 30)
        if (failed && t.error != null) NMeta(t.error!!, color = N.accent, maxLines = 2)
    }
}

@Composable
fun TextRow(x: TextItem, onCopy: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().nCard(24.dp).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        NIcon(if (x.clipboard) Ic.CLIPBOARD else Ic.TEXT, tint = N.secondary, size = 22.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            NText(x.text, style = NType.body, maxLines = 4)
            NMeta((if (x.incoming) tr("from ", "от ") else tr("to ", "для ")) + x.peerName + " · " + formatTime(x.time))
        }
        NIconButton(Ic.CLIPBOARD, onCopy, tint = N.secondary, size = 40.dp)
    }
}

@Composable
fun PairDialog(req: PairingRequest, onAnswer: (Boolean) -> Unit) {
    Box(
        Modifier.fillMaxSize().background(N.bg.copy(alpha = 0.92f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).fillMaxWidth().nCard(28.dp, outlined = true).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NCaps(tr("Pair with", "Сопряжение с") + " " + req.peerName)
            NText(req.code.chunked(3).joinToString(" "), style = NType.dotDisplay)
            NText(
                tr("Check that the same code is shown on ${req.peerName}. Only continue if both match.", "Проверьте, что такой же код показан на устройстве ${req.peerName}. Продолжайте, только если коды совпадают."),
                style = NType.quote, color = N.secondary, align = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                NButton(tr("Cancel", "Отмена"), { onAnswer(false) }, Modifier.weight(1f))
                NButton(tr("Match", "Совпадает"), { onAnswer(true) }, Modifier.weight(1f), kind = BtnKind.FILLED, icon = Ic.CHECK)
            }
        }
    }
}

@Composable
fun SectionCaps(text: String, modifier: Modifier = Modifier) = NCaps(text, modifier.padding(top = 22.dp, bottom = 4.dp, start = 4.dp))

@Composable
fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().nCard(24.dp).clickable { onChange(!checked) }.padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            NText(title, style = NType.bodyMedium, maxLines = 3)
            NMeta(sub, maxLines = 3)
        }
        NSwitch(checked, onChange)
    }
}

@Composable
fun Gap(h: Int) = Spacer(Modifier.height(h.dp))

@Composable
fun Gapw(w: Int) = Spacer(Modifier.width(w.dp))
