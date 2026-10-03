@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.essentialshare.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.essentialshare.core.Direction
import dev.essentialshare.core.LinkState
import dev.essentialshare.core.PairingRequest
import dev.essentialshare.core.PeerInfo
import dev.essentialshare.core.Platform
import dev.essentialshare.core.TransferInfo
import dev.essentialshare.core.TransferState
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

private fun promoState(): UiState {
    val now = System.currentTimeMillis()
    fun t(id: Long, dir: Direction, name: String, size: Long, done: Long, st: TransferState, speed: Long = 0, res: String? = null) =
        TransferInfo(id, dir, "p1", "Nothing Phone (3a)", name, size, done, st, speed, now - id * 1000, 0, res, null, false)
    return UiState(
        deviceName = "STUDIO-PC",
        peers = listOf(
            PeerInfo("p1", "Nothing Phone (3a)", Platform.ANDROID, "192.168.0.14", 40100, true, LinkState.READY),
            PeerInfo("p2", "Pixel 9", Platform.ANDROID, "192.168.0.31", 40101, false, LinkState.SEEN),
        ),
        transfers = listOf(
            t(1, Direction.OUT, "Project-final.zip", 842_000_000, 540_000_000, TransferState.ACTIVE, speed = 38_400_000),
            t(2, Direction.IN, "IMG_20261002_184011.jpg", 4_200_000, 4_200_000, TransferState.DONE, res = "C:/x/IMG.jpg"),
            t(3, Direction.IN, "voice-note.m4a", 1_800_000, 1_800_000, TransferState.DONE, res = "C:/x/voice.m4a"),
        ),
        selectedId = "p1",
        saveDir = "C:\\Users\\you\\Downloads\\Essential Share",
    )
}

@Composable
private fun DotGrid() {
    Canvas(Modifier.fillMaxSize()) {
        val step = 44.dp.toPx()
        var y = step / 2
        while (y < size.height) {
            var x = step / 2
            while (x < size.width) {
                drawCircle(N.display.copy(alpha = 0.09f), 1.6.dp.toPx(), Offset(x, y)); x += step
            }
            y += step
        }
    }
}

/** The real app screen, scaled down into a rounded window. */
@Composable
private fun AppWindow(ui: UiState, scale: Float) {
    val shape = RoundedCornerShape(30.dp)
    Box(Modifier.size((1060 * scale).dp, (720 * scale).dp).clip(shape).border(2.dp, N.borderVisible, shape).background(N.bg)) {
        Box(
            Modifier.requiredSize(1060.dp, 720.dp).align(Alignment.Center)
                .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin.Center },
        ) { AppScreen(ui, object : Actions {}) }
    }
}

@Composable
private fun PillLabel(text: String, fill: Color, ink: Color) {
    Box(Modifier.height(78.dp).clip(RoundedCornerShape(50)).background(fill).padding(horizontal = 40.dp), contentAlignment = Alignment.Center) {
        NText(text.uppercase(), style = NType.bodyMedium.copy(fontSize = 26.sp), color = ink)
    }
}

@Composable
private fun Phone(shot: File?, w: Int, h: Int) {
    val bezel = RoundedCornerShape((w * 0.16f).dp)
    Box(Modifier.size(w.dp, h.dp).clip(bezel).background(Color(0xFF111111)).padding((w * 0.028f).dp)) {
        val inner = RoundedCornerShape((w * 0.135f).dp)
        Box(Modifier.fillMaxSize().clip(inner).background(N.bg), contentAlignment = Alignment.Center) {
            if (shot != null && shot.exists()) {
                Image(
                    org.jetbrains.skia.Image.makeFromEncoded(shot.readBytes()).toComposeImageBitmap(),
                    null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    NIcon(Ic.PHONE, size = 120.dp, tint = N.secondary)
                    NCaps("put your phone screenshot here", color = N.secondary)
                    NCaps("promo/phone1.png", color = N.disabled)
                }
            }
        }
        Box(Modifier.align(Alignment.TopCenter).padding(top = (w * 0.05f).dp).size((w * 0.055f).dp).clip(RoundedCornerShape(50)).background(Color(0xFF111111)))
    }
}

/** The lockup plus a pill that says this is a fan project. */
@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Logotype()
        Box(Modifier.clip(RoundedCornerShape(50)).border(1.5.dp, N.borderVisible, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp)) {
            NCaps("FAN-MADE . UNOFFICIAL", color = N.secondary)
        }
    }
}

@Composable
private fun Disclaimer() {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(84.dp).background(N.bg), contentAlignment = Alignment.Center) {
            NCaps("UNOFFICIAL FAN PROJECT . NOT AFFILIATED WITH, ENDORSED BY OR SPONSORED BY NOTHING TECHNOLOGY", color = N.secondary)
        }
    }
}

/** Draws [content] at its natural size and scales it, so small widgets can be shown large. */
@Composable
private fun Scaled(w: Int, h: Int, scale: Float, content: @Composable () -> Unit) {
    Box(Modifier.size((w * scale).dp, (h * scale).dp)) {
        Box(
            Modifier.requiredSize(w.dp, h.dp).align(Alignment.Center)
                .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin.Center },
        ) { content() }
    }
}

@Composable
private fun Marks() {
    Box(Modifier.fillMaxSize()) {
        CrossMark(Modifier.align(Alignment.TopStart).padding(40.dp), 28.dp)
        CrossMark(Modifier.align(Alignment.TopEnd).padding(40.dp), 28.dp)
        CrossMark(Modifier.align(Alignment.BottomStart).padding(40.dp), 28.dp)
        CrossMark(Modifier.align(Alignment.BottomEnd).padding(40.dp), 28.dp)
    }
}

@Composable
private fun CardPrivate() {
    Box(Modifier.fillMaxSize().background(N.bg)) {
        DotGrid(); Marks()
        Column(Modifier.align(Alignment.CenterStart).padding(start = 130.dp).width(840.dp), verticalArrangement = Arrangement.spacedBy(34.dp)) {
            Brand()
            NText("Private\nby design.", style = NType.title.copy(fontSize = 130.sp, lineHeight = 132.sp))
            NText("Every link is encrypted end to end. New devices must show the same code before anything is sent.", style = NType.quote.copy(fontSize = 28.sp, lineHeight = 40.sp), color = N.secondary)
        }
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 150.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(46.dp)) {
            NIcon(Ic.SHIELD, size = 420.dp, tint = N.display)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NCaps("X25519 KEY EXCHANGE", color = N.display)
                NCaps("AES-256-GCM", color = N.secondary)
                NCaps("NO ACCOUNTS . NO CLOUD", color = N.secondary)
            }
        }
    }
}

@Composable
private fun CardTypes() {
    Box(Modifier.fillMaxSize().background(N.bg)) {
        DotGrid(); Marks()
        Column(Modifier.align(Alignment.TopStart).padding(start = 130.dp, top = 130.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
            Brand()
            NText("Anything.\nBoth ways.", style = NType.title.copy(fontSize = 118.sp, lineHeight = 120.sp))
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            listOf("FILES" to Ic.FILE, "FOLDERS" to Ic.FOLDER, "PHOTOS" to Ic.IMAGE, "TEXT" to Ic.TEXT, "CLIPBOARD" to Ic.CLIPBOARD).forEach { (label, icon) ->
                Column(
                    Modifier.size(320.dp, 300.dp).clip(RoundedCornerShape(44.dp)).background(N.surface).padding(34.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    NIcon(icon, size = 130.dp, tint = N.display)
                    NCaps(label, color = N.secondary)
                }
            }
        }
    }
}


/** Flat blocks scattered behind the content, in the brand colours. */
@Composable
private fun FlatBlocks() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color(0xFFC8102E), Offset(150f, 610f), androidx.compose.ui.geometry.Size(150f, 150f))
        drawCircle(Color(0xFFFFC700), 92f, Offset(1840f, 560f))
        drawCircle(Color(0xFF002F6C), 56f, Offset(250f, 905f))
        drawCircle(Color(0xFFEBB3C6), 64f, Offset(1730f, 835f))
        drawRect(Color(0xFFFFC700), Offset(60f, 800f), androidx.compose.ui.geometry.Size(170f, 96f))
        drawRect(Color(0xFFC8102E), Offset(1880f, 700f), androidx.compose.ui.geometry.Size(70f, 220f))
    }
}

/** 1. Centered headline, the real window rising from the bottom edge. */
@Composable
private fun CardHero() {
    Box(Modifier.fillMaxSize().background(N.bg)) {
        DotGrid(); Marks(); FlatBlocks()
        Column(Modifier.align(Alignment.TopCenter).padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(26.dp)) {
            Brand()
            NText("Send anything.\nInstantly.", style = NType.title.copy(fontSize = 132.sp, lineHeight = 134.sp), align = androidx.compose.ui.text.style.TextAlign.Center)
            NText("Phone to PC over your own Wi-Fi. No cloud, no accounts.", style = NType.quote.copy(fontSize = 28.sp, lineHeight = 40.sp), color = N.secondary, align = androidx.compose.ui.text.style.TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PillLabel("Files", N.yellow, Color(0xFF1C1C1C))
                PillLabel("Photos", N.ink, N.onInk)
                PillLabel("Text", N.ink, N.onInk)
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).offset(y = 270.dp)) { AppWindow(promoState(), 1.0f) }
    }
}

@Composable
private fun Tile(modifier: Modifier, fill: Color, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(44.dp)).background(fill).padding(38.dp)) { content() }
}

/** 2. Bento grid: one tile per idea, each built from the real components. */
@Composable
private fun CardBento() {
    val ink = Color(0xFF1C1C1C)
    Box(Modifier.fillMaxSize().background(N.bg)) {
        DotGrid(); Marks()
        Column(Modifier.align(Alignment.CenterStart).padding(start = 130.dp).width(520.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
            Brand()
            NText("All in\none place.", style = NType.title.copy(fontSize = 112.sp, lineHeight = 114.sp))
            NText("Speed, pairing, devices and files, one calm screen.", style = NType.quote.copy(fontSize = 26.sp, lineHeight = 38.sp), color = N.secondary)
        }
        val tw = 392.dp
        val th = 440.dp
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 100.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Tile(Modifier.size(tw, th), N.yellow) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                        NIcon(Ic.SEND, size = 110.dp, tint = ink)
                        Column {
                            NText("36.6", style = NType.dotDisplay.copy(fontSize = 112.sp), color = ink)
                            NCaps("MB/S", color = ink)
                        }
                    }
                }
                Tile(Modifier.size(tw, th), Color(0xFF1C1C1C)) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                        NCaps("PAIRING CODE", color = Color(0xFF929292))
                        NText("482\n913", style = NType.dotDisplay.copy(fontSize = 104.sp, lineHeight = 112.sp), color = Color(0xFFF2F2F2))
                    }
                }
                Tile(Modifier.size(tw, th), Color(0xFFC8102E)) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                        NIcon(Ic.SHIELD, size = 140.dp, tint = Color.White)
                        NCaps("ENCRYPTED END TO END", color = Color.White)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Tile(Modifier.size(tw, th), N.surface) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                        NCaps("SENDING", color = N.secondary)
                        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            NText("Project-final.zip", style = NType.bodyMedium.copy(fontSize = 24.sp), color = ink)
                            DotBar(0.64f, color = ink, cells = 22)
                            NCaps("803 MB . 64%", color = N.secondary)
                        }
                    }
                }
                Tile(Modifier.size(tw, th), N.surface) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                        NCaps("NEARBY . 02", color = N.secondary)
                        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            listOf(Triple("Nothing Phone (3a)", "CONNECTED", Ic.PHONE), Triple("Studio PC", "ONLINE", Ic.PC)).forEach { (n, st, ic) ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    NIcon(ic, size = 52.dp, tint = ink)
                                    Column {
                                        NText(n, style = NType.bodyMedium.copy(fontSize = 22.sp), color = ink, maxLines = 1)
                                        NCaps(st, color = N.secondary)
                                    }
                                }
                            }
                        }
                    }
                }
                Tile(Modifier.size(tw, th), N.surfaceRaised) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                        NCaps("SEND", color = N.secondary)
                        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) { NIcon(Ic.FILE, size = 84.dp, tint = ink); NIcon(Ic.FOLDER, size = 84.dp, tint = ink) }
                            Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) { NIcon(Ic.TEXT, size = 84.dp, tint = ink); NIcon(Ic.CLIPBOARD, size = 84.dp, tint = ink) }
                        }
                    }
                }
            }
        }
    }
}

/** 3. Dark: the pairing code as the hero, the two devices and the link under it. */
@Composable
private fun CardCode() {
    Box(Modifier.fillMaxSize().background(N.bg)) {
        DotGrid(); Marks()
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(36.dp)) {
            Brand()
            NText("482 913", style = NType.dotDisplay.copy(fontSize = 250.sp, lineHeight = 260.sp), color = N.display)
            NText("The same code on both screens.", style = NType.title.copy(fontSize = 64.sp, lineHeight = 70.sp), color = N.display, align = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Scaled(284, 132, 2.5f) {
                ScanPanel(listOf(RadarDot("p1", true, true, true)), "Match", Modifier.size(284.dp, 132.dp))
            }
        }
    }
}

/** Fast: this PC on the left, the phone on the right, streams of dots between them. */
@Composable
private fun CardFast() {
    Box(Modifier.fillMaxSize().background(N.bg)) {
        DotGrid(); Marks()
        Column(Modifier.align(Alignment.TopCenter).padding(top = 90.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(26.dp)) {
            Brand()
            NText("Over your Wi-Fi.\nNo cloud in between.", style = NType.title.copy(fontSize = 112.sp, lineHeight = 116.sp), align = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Canvas(Modifier.align(Alignment.BottomCenter).padding(bottom = 130.dp).size(1700.dp, 400.dp)) {
            val gp = 15.dp.toPx()
            val gw = gp * 9
            val top = size.height / 2 - gw / 2
            val ink = N.ink
            fun glyph(name: String, x: Float, color: Color) {
                val pat = DOT_ICONS[name]!!
                for (r in pat.indices) for (c in pat[r].indices) if (pat[r][c] == '#') drawCircle(color, gp * 0.4f, Offset(x + c * gp + gp / 2, top + r * gp + gp / 2))
            }
            glyph("PC", 0f, ink)
            glyph("PHONE", size.width - gw, N.yellow)
            val x0 = gw + 60.dp.toPx()
            val x1 = size.width - gw - 60.dp.toPx()
            val pitch = 24.dp.toPx()
            val n = ((x1 - x0) / pitch).toInt()
            // three lanes, each with its own packet at a different point of its run
            val lanes = listOf(-1, 0, 1)
            val heads = listOf(0.62f, 0.38f, 0.86f)
            lanes.forEachIndexed { li, lane ->
                val cy = size.height / 2 + lane * 46.dp.toPx()
                val head = heads[li] * (n + 8) - 4
                for (i in 0..n) {
                    val d = head - i
                    val glow = if (d in 0f..9f) 1f - d / 9f else 0f
                    val x = x0 + i * (x1 - x0) / n
                    val col = if (d in 0f..1f) (if (li == 1) Color(0xFFD71921) else N.yellow) else ink
                    drawCircle(col.copy(alpha = 0.16f + 0.84f * glow), (2.4f + 4.2f * glow).dp.toPx(), Offset(x, cy))
                }
            }
        }
        NText("36.6 MB/s", Modifier.align(Alignment.BottomCenter).padding(bottom = 520.dp - 400.dp + 40.dp), NType.dotNumber.copy(fontSize = 34.sp), N.secondary)
    }
}

/** Free on GitHub. */
@Composable
private fun CardGithub() {
    Box(Modifier.fillMaxSize().background(N.bg)) {
        DotGrid(); Marks()
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(34.dp)) {
            Brand()
            NIcon(Ic.DOWN, size = 300.dp, tint = N.yellow)
            NText("Free on GitHub.", style = NType.title.copy(fontSize = 124.sp, lineHeight = 128.sp), align = androidx.compose.ui.text.style.TextAlign.Center)
            NText("Windows app and Android APK, ready to download.", style = NType.quote.copy(fontSize = 28.sp, lineHeight = 40.sp), color = N.secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf("WINDOWS", "ANDROID", "FREE").forEach { t ->
                    Box(Modifier.clip(RoundedCornerShape(50)).border(2.dp, N.borderVisible, RoundedCornerShape(50)).padding(horizontal = 34.dp, vertical = 16.dp)) {
                        NCaps(t, color = N.display)
                    }
                }
            }
        }
    }
}

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "promo").also { it.mkdirs() }
    val phone = File(out, "phone1.png")
    fun render(name: String, light: Boolean, content: @Composable () -> Unit) {
        ThemeState.mode = if (light) ThemeMode.LIGHT else ThemeMode.DARK
        val scene = ImageComposeScene(2000, 1125, Density(1f)) { Box(Modifier.fillMaxSize()) { content(); Disclaimer() } }
        scene.render(0)
        val img = scene.render(1_000_000_000L)
        File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }
    Lang.ru = false
    render("promo-1-hero", true) { CardHero() }
    render("promo-2-bento", true) { CardBento() }
    render("promo-3-code-dark", false) { CardCode() }
    render("promo-4-fast-wifi", true) { CardFast() }
    render("promo-5-private-dark", false) { CardPrivate() }
    render("promo-6-anything", true) { CardTypes() }
    render("promo-7-github-dark", false) { CardGithub() }
    println("promo cards in $out")
}
