package dev.essentialshare.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Essential look, taken from essential.com, the Nothing Playground and the Nothing brand reference:
 * N-Grey page, white flat cards, N-Red / N-Yellow / N-Blue as the only colours, serif headlines,
 * Geist Mono for text and labels, dot-matrix type for names and numbers. No gradients, no shadows.
 */
@Immutable
class NColors(
    val bg: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val border: Color,
    val borderVisible: Color,
    val display: Color,
    val primary: Color,
    val secondary: Color,
    val disabled: Color,
    val accent: Color,
    val yellow: Color,
    val blue: Color,
    val ink: Color,
    val onInk: Color,
    val success: Color,
)

val NLight = NColors(
    bg = Color(0xFFF2F2F2), surface = Color(0xFFFFFFFF), surfaceRaised = Color(0xFFE3E3E3),
    border = Color(0xFFE8E8E8), borderVisible = Color(0xFFD2D2D2),
    display = Color(0xFF1C1C1C), primary = Color(0xFF1C1C1C), secondary = Color(0xFF777777), disabled = Color(0xFFB0B0B0),
    accent = Color(0xFFC8102E), yellow = Color(0xFFFFC700), blue = Color(0xFF002F6C),
    ink = Color(0xFF1C1C1C), onInk = Color(0xFFF2F2F2), success = Color(0xFF1C1C1C),
)

val NDarkColors = NColors(
    bg = Color(0xFF000000), surface = Color(0xFF1C1C1C), surfaceRaised = Color(0xFF2D2D2D),
    border = Color(0xFF262626), borderVisible = Color(0xFF3A3A3A),
    display = Color(0xFFF2F2F2), primary = Color(0xFFE3E3E3), secondary = Color(0xFF929292), disabled = Color(0xFF606060),
    accent = Color(0xFFD71920), yellow = Color(0xFFFFC700), blue = Color(0xFF3F6FB8),
    ink = Color(0xFFF2F2F2), onInk = Color(0xFF1C1C1C), success = Color(0xFFF2F2F2),
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Dark is the default; light is chosen by the person or follows a light system theme. */
object ThemeState {
    var mode by mutableStateOf(ThemeMode.SYSTEM)
    var systemDark by mutableStateOf(true)
    val light: Boolean
        get() = when (mode) {
            ThemeMode.LIGHT -> true
            ThemeMode.DARK -> false
            ThemeMode.SYSTEM -> !systemDark
        }
}

val N: NColors get() = if (ThemeState.light) NLight else NDarkColors

private fun resFont(path: String) = FontFamily(Font(resource = path, weight = FontWeight.Normal))

// Nothing's own faces (Latin only) ...
val DotFont: FontFamily by lazy { resFont("font/NDot-55.otf") }
val HeadFont: FontFamily by lazy { resFont("font/NType82-Headline.otf") }
val QuoteFont: FontFamily by lazy { resFont("font/NType82-Regular.otf") }
val MonoFont: FontFamily by lazy { resFont("font/NType82Mono-Regular.otf") }

// ... and stand-ins with Cyrillic, used for any string that contains Russian letters
val DotCyr: FontFamily by lazy { resFont("font/matrix_print.ttf") }
val HeadCyr: FontFamily by lazy { resFont("font/oranienbaum.ttf") }
val MonoCyr: FontFamily by lazy {
    FontFamily(
        Font(resource = "font/GeistMono-Regular.ttf", weight = FontWeight.Normal),
        Font(resource = "font/GeistMono-Medium.ttf", weight = FontWeight.Medium),
    )
}

object NType {
    val title get() = TextStyle(fontFamily = HeadFont, fontSize = 60.sp, lineHeight = 62.sp)
    val headline get() = TextStyle(fontFamily = HeadFont, fontSize = 30.sp, lineHeight = 34.sp)
    val dotDisplay get() = TextStyle(fontFamily = DotFont, fontSize = 56.sp, letterSpacing = 0.06.em)
    val dotNumber get() = TextStyle(fontFamily = DotFont, fontSize = 22.sp)
    /** Small upper-case mono label, as on the Essential site. */
    val caps get() = TextStyle(fontFamily = MonoFont, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.06.em)
    val label get() = TextStyle(fontFamily = MonoFont, fontSize = 12.sp, lineHeight = 17.sp)
    val body get() = TextStyle(fontFamily = MonoFont, fontSize = 13.sp, lineHeight = 20.sp)
    val bodyMedium get() = TextStyle(fontFamily = MonoFont, fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val quote get() = TextStyle(fontFamily = QuoteFont, fontSize = 14.sp, lineHeight = 21.sp)
    val heading get() = TextStyle(fontFamily = MonoFont, fontSize = 16.sp, fontWeight = FontWeight.Medium)
}

/**
 * The Nothing faces have no Cyrillic. In Russian (or for any string with Russian letters) the app uses only two
 * families from Glyph Clock: the dot-matrix MatrixSans for headlines, captions, numbers and the logotype, and
 * Geist Mono for everything else.
 */
fun TextStyle.forText(text: String, caps: Boolean = false): TextStyle {
    if (!Lang.ru && text.none { it in '\u0400'..'\u04FF' }) return this
    val family = when (fontFamily) {
        DotFont, HeadFont, QuoteFont -> DotCyr
        MonoFont -> if (caps) DotCyr else MonoCyr
        else -> fontFamily
    }
    // the dot face is drawn small, so captions set in it get a little more size
    return if (caps && family == DotCyr) copy(fontFamily = family, fontSize = 13.sp, lineHeight = 17.sp) else copy(fontFamily = family)
}

// ---- text ------------------------------------------------------------------------------------

@Composable
fun NText(
    text: String, modifier: Modifier = Modifier, style: TextStyle = NType.body, color: Color = N.display,
    align: TextAlign? = null, maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text, modifier, style = style.forText(text).copy(color = color, textAlign = align ?: TextAlign.Unspecified),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun NMeta(text: String, modifier: Modifier = Modifier, color: Color = N.secondary, maxLines: Int = 1) =
    NText(text, modifier, NType.label, color, maxLines = maxLines)

@Composable
fun NCaps(text: String, modifier: Modifier = Modifier, color: Color = N.secondary) {
    BasicText(
        text.uppercase(), modifier, style = NType.caps.forText(text, caps = true).copy(color = color), maxLines = 1, softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

// ---- surfaces and controls ---------------------------------------------------------------------

fun Modifier.nCard(radius: Dp = 24.dp, color: Color = N.surface, outlined: Boolean = false, borderColor: Color = N.borderVisible): Modifier {
    val shape = RoundedCornerShape(radius)
    return this.clip(shape).background(color, shape).then(if (outlined) Modifier.border(1.dp, borderColor, shape) else Modifier)
}

@Composable
fun Modifier.nClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
        .clickable(enabled = enabled, onClick = onClick)

enum class BtnKind { FILLED, DARK, OUTLINED, GHOST, DANGER }

/** Pill buttons as on the Essential site: yellow primary, ink pill, outlined, ghost, red for destructive. */
@Composable
fun NButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: BtnKind = BtnKind.OUTLINED,
    icon: Ic? = null, enabled: Boolean = true,
) {
    val src = remember { MutableInteractionSource() }
    val hover by src.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when (kind) {
            BtnKind.FILLED -> if (hover) Color(0xFFE8B500) else N.yellow
            BtnKind.DARK -> if (hover) N.ink.copy(alpha = 0.82f) else N.ink
            BtnKind.DANGER -> if (hover) Color(0xFFE5323A) else N.accent
            BtnKind.OUTLINED -> if (hover) N.surfaceRaised else Color.Transparent
            BtnKind.GHOST -> if (hover) N.surfaceRaised else Color.Transparent
        }, tween(110),
    )
    val fg = when (kind) {
        BtnKind.FILLED -> Color(0xFF1C1C1C)
        BtnKind.DARK -> N.onInk
        BtnKind.DANGER -> Color.White
        else -> N.display
    }.let { if (enabled) it else N.disabled }
    val shape = RoundedCornerShape(50)
    Row(
        modifier.height(40.dp).clip(shape).background(if (enabled || kind == BtnKind.GHOST || kind == BtnKind.OUTLINED) bg else N.surfaceRaised, shape)
            .then(if (kind == BtnKind.OUTLINED) Modifier.border(1.dp, if (enabled) N.borderVisible else N.border, shape) else Modifier)
            .hoverable(src).nClickable(enabled, onClick).padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) NIcon(icon, tint = fg, size = 18.dp)
        if (text.isNotEmpty()) BasicText(text.uppercase(), style = NType.bodyMedium.forText(text).copy(color = fg, letterSpacing = 0.03.em), maxLines = 1, softWrap = false)
    }
}

@Composable
fun NIconButton(ic: Ic, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = N.display, size: Dp = 40.dp) {
    val src = remember { MutableInteractionSource() }
    val hover by src.collectIsHoveredAsState()
    Box(
        modifier.size(size).clip(CircleShape).background(if (hover) N.surfaceRaised else Color.Transparent, CircleShape)
            .hoverable(src).nClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { NIcon(ic, tint = tint, size = 20.dp) }
}

/** Mechanical two-state switch. */
@Composable
fun NSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val track by animateColorAsState(if (checked) N.ink else N.surfaceRaised, tween(120))
    val knob by animateColorAsState(if (checked) N.onInk else N.secondary, tween(120))
    val x by androidx.compose.animation.core.animateDpAsState(if (checked) 22.dp else 2.dp, tween(120))
    Box(
        Modifier.width(46.dp).height(26.dp).clip(CircleShape).background(track, CircleShape).nClickable { onChange(!checked) },
    ) {
        Box(Modifier.padding(start = x, top = 3.dp).size(20.dp).background(knob, CircleShape))
    }
}

/** Segmented progress bar made of small squares, like the dot grid in the Glyph matrix. */
@Composable
fun DotBar(fraction: Float, modifier: Modifier = Modifier, active: Boolean = true, color: Color = N.display, cells: Int = 36) {
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (cells - 1)) / cells
        val filled = (fraction.coerceIn(0f, 1f) * cells)
        for (i in 0 until cells) {
            val on = i < filled.toInt()
            val partial = i == filled.toInt() && active && filled - filled.toInt() > 0.02f
            val c = when {
                on -> color
                partial -> color.copy(alpha = 0.5f)
                else -> N.surfaceRaised
            }
            drawRoundRect(c, Offset(i * (w + gap), 0f), Size(w, size.height), androidx.compose.ui.geometry.CornerRadius(w * 0.3f))
        }
    }
}

// ---- icons (monoline, round caps) --------------------------------------------------------------

enum class Ic { BACK, IMAGE, PHONE, PC, FILE, FOLDER, SEND, CLIPBOARD, TEXT, GEAR, CLOSE, MINIMIZE, CHECK, TRASH, OPEN, PLUS, LINK, DOWN, UP, SHIELD, SEARCH, REFRESH, PIN }

@Composable
fun NIcon(ic: Ic, modifier: Modifier = Modifier, tint: Color = N.display, size: Dp = 24.dp) {
    val dots = DOT_ICONS[ic.name]
    Canvas(modifier.size(size)) {
        if (dots != null) { drawDotPattern(dots, tint); return@Canvas }
        val s = this.size.minDimension / 24f
        val w = 1.6f * s
        val st = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, p(x1, y1), p(x2, y2), w, StrokeCap.Round)
        fun poly(vararg v: Float, close: Boolean = false, fill: Boolean = false) {
            val path = Path().apply {
                moveTo(v[0] * s, v[1] * s)
                for (i in 2 until v.size step 2) lineTo(v[i] * s, v[i + 1] * s)
                if (close) close()
            }
            drawPath(path, tint, style = if (fill) Fill else st)
        }
        fun circle(cx: Float, cy: Float, r: Float, fill: Boolean = false) =
            drawCircle(tint, r * s, p(cx, cy), style = if (fill) Fill else st)
        fun rrect(x: Float, y: Float, wd: Float, ht: Float, r: Float) =
            drawRoundRect(tint, p(x, y), Size(wd * s, ht * s), androidx.compose.ui.geometry.CornerRadius(r * s), style = st)

        when (ic) {
            Ic.BACK, Ic.IMAGE -> {} // dot-matrix only (see DOT_ICONS)
            Ic.PHONE -> { rrect(7f, 2.8f, 10f, 18.4f, 2.4f); line(10.8f, 18.2f, 13.2f, 18.2f) }
            Ic.PC -> { rrect(3f, 4.5f, 18f, 11.5f, 2f); line(8.5f, 20f, 15.5f, 20f); line(12f, 16f, 12f, 20f) }
            Ic.FILE -> { poly(6f, 3f, 14f, 3f, 19f, 8f, 19f, 21f, 6f, 21f, close = true); poly(14f, 3f, 14f, 8f, 19f, 8f) }
            Ic.FOLDER -> poly(3f, 6f, 9.5f, 6f, 11.5f, 8.5f, 21f, 8.5f, 21f, 19f, 3f, 19f, close = true)
            Ic.SEND -> { poly(3.5f, 11f, 20.5f, 3.5f, 13f, 20.5f, 11f, 13f, close = true); line(11f, 13f, 20.5f, 3.5f) }
            Ic.CLIPBOARD -> { rrect(5f, 4.5f, 14f, 17f, 2.2f); rrect(9f, 2.5f, 6f, 4f, 1.4f); line(8.5f, 12f, 15.5f, 12f); line(8.5f, 15.5f, 13f, 15.5f) }
            Ic.TEXT -> { line(5f, 6f, 19f, 6f); line(12f, 6f, 12f, 19f); line(9f, 19f, 15f, 19f) }
            Ic.GEAR -> {
                circle(12f, 12f, 3.2f); circle(12f, 12f, 7.4f)
                for (k in 0 until 8) {
                    val a = k * PI.toFloat() / 4f
                    line(12f + 8.6f * cos(a), 12f + 8.6f * sin(a), 12f + 10.6f * cos(a), 12f + 10.6f * sin(a))
                }
            }
            Ic.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            Ic.MINIMIZE -> line(6f, 12f, 18f, 12f)
            Ic.CHECK -> poly(5f, 12.5f, 10f, 17.5f, 19f, 7f)
            Ic.TRASH -> { line(4.5f, 7f, 19.5f, 7f); poly(9f, 7f, 9f, 4f, 15f, 4f, 15f, 7f); poly(6.5f, 7f, 7.5f, 20f, 16.5f, 20f, 17.5f, 7f) }
            Ic.OPEN -> { poly(13f, 4f, 20f, 4f, 20f, 11f); line(20f, 4f, 11f, 13f); poly(17f, 14f, 17f, 20f, 4f, 20f, 4f, 7f, 10f, 7f) }
            Ic.PLUS -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            Ic.LINK -> { rrect(3f, 9f, 11f, 6f, 3f); rrect(10f, 9f, 11f, 6f, 3f) }
            Ic.DOWN -> { line(12f, 4f, 12f, 17f); poly(6f, 11.5f, 12f, 17.5f, 18f, 11.5f); line(5f, 21f, 19f, 21f) }
            Ic.UP -> { line(12f, 20f, 12f, 7f); poly(6f, 12.5f, 12f, 6.5f, 18f, 12.5f); line(5f, 3f, 19f, 3f) }
            Ic.SHIELD -> { poly(12f, 3f, 19f, 6f, 19f, 12f, 12f, 21f, 5f, 12f, 5f, 6f, close = true); poly(9f, 12f, 11.2f, 14.2f, 15f, 9.8f) }
            Ic.SEARCH -> { circle(10.5f, 10.5f, 6f); line(15f, 15f, 20f, 20f) }
            Ic.REFRESH -> { drawArc(tint, 40f, 280f, false, p(4f, 4f), Size(16f * s, 16f * s), style = st); poly(17.5f, 2.5f, 18f, 7.5f, 13f, 8f) }
            Ic.PIN -> { circle(12f, 10f, 4f); line(12f, 14f, 12f, 21f) }
        }
    }
}

// ---- the one expressive moment: a dot-matrix radar -----------------------------------------------

/** The small red recording-style dot shown while bytes are moving. */
@Composable
fun PulseDot(modifier: Modifier = Modifier, color: Color = N.accent, size: Dp = 8.dp) {
    val t = rememberInfiniteTransition()
    val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(850), RepeatMode.Reverse))
    Box(modifier.size(size).background(color.copy(alpha = a), CircleShape))
}

// ---- brand details ---------------------------------------------------------------------------

/** Thin "+" registration mark from the Essential site grid. */
@Composable
fun CrossMark(modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Canvas(modifier.size(size)) {
        val c = N.borderVisible
        val w = 1.dp.toPx()
        drawLine(c, Offset(0f, this.size.height / 2), Offset(this.size.width, this.size.height / 2), w)
        drawLine(c, Offset(this.size.width / 2, 0f), Offset(this.size.width / 2, this.size.height), w)
    }
}

/** Flat blocks in the primary colours, like the Essential site hero: no gradients, no shadows. */
@Composable
fun ShapesArt(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val red = Color(0xFFC8102E)
        val yellow = Color(0xFFFFC700)
        val blue = Color(0xFF002F6C)
        val pink = Color(0xFFEBB3C6)
        drawRect(red, Offset(w * 0.04f, h * 0.18f), Size(h * 0.46f, h * 0.46f))
        drawCircle(yellow, h * 0.24f, Offset(w * 0.30f, h * 0.38f))
        drawRect(red, Offset(w * 0.44f, 0f), Size(h * 0.30f, h * 0.88f))
        drawCircle(pink, h * 0.17f, Offset(w * 0.60f, h * 0.30f))
        drawRect(yellow, Offset(w * 0.68f, h * 0.42f), Size(h * 0.70f, h * 0.40f))
        drawCircle(blue, h * 0.20f, Offset(w * 0.90f, h * 0.24f))
    }
}

/** Dot-matrix lockup: brand in capitals, product in lowercase with spaced brackets (Nothing naming rules). */
@Composable
fun Logotype(modifier: Modifier = Modifier, color: Color = N.display) {
    BasicText(
        "ESSENTIAL SHARE", modifier,
        style = TextStyle(fontFamily = DotFont, fontSize = 13.sp, letterSpacing = 0.04.em, color = color).forText(""), maxLines = 1, softWrap = false,
    )
}

// ---- dot-matrix icons -------------------------------------------------------------------------

/** 9x9 pixel patterns drawn as round dots, in the spirit of Nothing's Glyph matrix and widgets. */
val DOT_ICONS: Map<String, List<String>> = mapOf(
    "PHONE" to listOf("..#####..", "..#...#..", "..#...#..", "..#...#..", "..#...#..", "..#...#..", "..#...#..", "..##.##..", "..#####.."),
    "PC" to listOf(".#######.", ".#.....#.", ".#.....#.", ".#.....#.", ".#######.", "....#....", "....#....", "..#####..", "........."),
    "FILE" to listOf(".####....", ".#..##...", ".#...###.", ".#.....#.", ".#.....#.", ".#.....#.", ".#.....#.", ".#.....#.", ".#######."),
    "FOLDER" to listOf(".........", ".###.....", "#...#####", "#.......#", "#.......#", "#.......#", "#.......#", "#########", "........."),
    "SEND" to listOf(".........", "....#....", ".....#...", "......#..", "#########", "......#..", ".....#...", "....#....", "........."),
    "BACK" to listOf(".........", "....#....", "...#.....", "..#......", "#########", "..#......", "...#.....", "....#....", "........."),
    "DOWN" to listOf("....#....", "....#....", "....#....", "....#....", ".#..#..#.", "..#.#.#..", "...###...", ".........", "#########"),
    "UP" to listOf("#########", ".........", "...###...", "..#.#.#..", ".#..#..#.", "....#....", "....#....", "....#....", "....#...."),
    "CLIPBOARD" to listOf("...###...", ".#######.", ".#.....#.", ".#.###.#.", ".#.....#.", ".#.###.#.", ".#.....#.", ".#######.", "........."),
    "TEXT" to listOf(".#######.", ".#######.", "....#....", "....#....", "....#....", "....#....", "....#....", "....#....", "........."),
    "GEAR" to listOf("....#....", ".#..#..#.", "..#####..", ".##...##.", "##..#..##", ".##...##.", "..#####..", ".#..#..#.", "....#...."),
    "CLOSE" to listOf("#.......#", ".#.....#.", "..#...#..", "...#.#...", "....#....", "...#.#...", "..#...#..", ".#.....#.", "#.......#"),
    "MINIMIZE" to listOf(".........", ".........", ".........", ".........", ".#######.", ".........", ".........", ".........", "........."),
    "CHECK" to listOf(".......#.", "......#..", "#....#...", ".#..#....", "..##....."),
    "TRASH" to listOf("...###...", "#########", ".#.....#.", ".#.#.#.#.", ".#.#.#.#.", ".#.#.#.#.", ".#######."),
    "OPEN" to listOf("....#####", ".......##", "......#.#", ".....#..#", "..#.#....", "...#.....", ".#.#.....", "#........"),
    "PLUS" to listOf("....#....", "....#....", "....#....", "....#....", "#########", "....#....", "....#....", "....#....", "....#...."),
    "IMAGE" to listOf("#########", "#.......#", "#...#...#", "#..#.#..#", "#.#...#.#", "##.....##", "#########"),
    "SHIELD" to listOf("#########", "#.......#", "#.......#", "#.......#", "#.......#", ".#.....#.", "..#...#..", "...#.#...", "....#...."),
)

/** Draws a pattern as round dots centred in this scope. */
fun DrawScope.drawDotPattern(pattern: List<String>, tint: Color, dotScale: Float = 0.4f) {
    val rows = pattern.size
    val cols = pattern.maxOf { it.length }
    val pitch = size.minDimension / maxOf(rows, cols, 9)
    val ox = (size.width - cols * pitch) / 2 + pitch / 2
    val oy = (size.height - rows * pitch) / 2 + pitch / 2
    for (r in 0 until rows) for (c in 0 until pattern[r].length) {
        if (pattern[r][c] == '#') drawCircle(tint, pitch * dotScale, Offset(ox + c * pitch, oy + r * pitch))
    }
}

class RadarDot(val key: String, val ready: Boolean, val selected: Boolean, val phone: Boolean = true)

/** Draws a pixel pattern with its top-left corner at ([x], [y]). */
private fun DrawScope.glyphAt(pattern: List<String>, x: Float, y: Float, pitch: Float, color: Color, alpha: Float, radius: Float) {
    for (r in pattern.indices) for (c in pattern[r].indices) {
        if (pattern[r][c] == '#') drawCircle(color.copy(alpha = alpha), radius, Offset(x + c * pitch + pitch / 2, y + r * pitch + pitch / 2))
    }
}

/**
 * Link panel in the style of Nothing's dot-matrix widgets: this device as a pixel icon on the left, the device
 * it can reach on the right, and a dotted line between them. While nothing is found a faint packet looks around;
 * once a device answers, packets of bright dots run to it and it lights up (yellow when selected).
 */
@Composable
fun ScanPanel(dots: List<RadarDot>, label: String, modifier: Modifier = Modifier, selfPhone: Boolean = false) {
    val t = rememberInfiniteTransition()
    val flow by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1900, easing = LinearEasing), RepeatMode.Restart))
    val blink by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse))
    val target = dots.firstOrNull { it.selected } ?: dots.firstOrNull { it.ready } ?: dots.firstOrNull()
    Box(modifier.nCard(28.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val gp = 5.5.dp.toPx()
            val gw = gp * 9
            val top = size.height / 2 - gw / 2 + 12.dp.toPx()
            val margin = 22.dp.toPx()
            val self = DOT_ICONS[if (selfPhone) "PHONE" else "PC"]!!
            // the far end is the other kind of device: a phone reaches a PC, a PC reaches a phone
            val otherIsPhone = target?.phone ?: !selfPhone
            val other = DOT_ICONS[if (otherIsPhone) "PHONE" else "PC"]!!
            val xRight = size.width - margin - gw
            glyphAt(self, margin, top, gp, N.ink, 0.95f, gp * 0.38f)

            val x0 = margin + gw + 16.dp.toPx()
            val x1 = xRight - 16.dp.toPx()
            val n = ((x1 - x0) / 9.dp.toPx()).toInt().coerceAtLeast(4)
            val cy = top + gw / 2
            val found = target != null
            val head = flow * (n + 8) - 4
            for (i in 0..n) {
                val d = head - i
                val glow = if (d in 0f..7f) 1f - d / 7f else 0f
                val x = x0 + i * (x1 - x0) / n
                val lead = found && d in 0f..1f
                val color = if (lead) (if (target!!.selected) N.yellow else N.display) else N.display
                val a = if (found) 0.2f + 0.8f * glow else 0.16f + 0.3f * glow
                drawCircle(color.copy(alpha = a), (1.7f + 2.1f * glow).dp.toPx(), Offset(x, cy))
            }
            if (found) {
                val arrive = if (flow > 0.8f) (flow - 0.8f) / 0.2f else 0f
                val c = if (target!!.selected) N.yellow else if (target.ready) N.ink else N.secondary
                glyphAt(other, xRight, top, gp, c, 1f, gp * (0.38f + 0.07f * arrive))
            } else {
                glyphAt(other, xRight, top, gp, N.secondary, 0.14f + 0.22f * blink, gp * 0.34f)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            PulseDot()
            Spacer(Modifier.width(8.dp))
            NCaps(label)
            Spacer(Modifier.weight(1f))
            NText("%02d".format(dots.size), style = NType.dotNumber)
        }
    }
}
