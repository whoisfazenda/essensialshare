@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.essentialshare.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.essentialshare.core.Direction
import dev.essentialshare.core.LinkState
import dev.essentialshare.core.PairingRequest
import dev.essentialshare.core.PeerInfo
import dev.essentialshare.core.Platform
import dev.essentialshare.core.TextItem
import dev.essentialshare.core.TransferInfo
import dev.essentialshare.core.TransferState
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

private fun fakeState(): UiState {
    val now = System.currentTimeMillis()
    fun t(id: Long, dir: Direction, name: String, size: Long, done: Long, st: TransferState, speed: Long = 0, ask: Boolean = false, res: String? = null) =
        TransferInfo(id, dir, "p1", "Nothing Phone (3a)", name, size, done, st, speed, now - id * 1000, 0, res, null, ask)
    return UiState(
        deviceName = "DESKTOP-ESSENTIAL",
        peers = listOf(
            PeerInfo("p1", "Nothing Phone (3a)", Platform.ANDROID, "192.168.0.14", 40100, true, LinkState.READY),
            PeerInfo("p2", "Pixel 9", Platform.ANDROID, "192.168.0.31", 40101, false, LinkState.SEEN),
            PeerInfo("p3", "Работа · ноутбук", Platform.WINDOWS, null, 0, true, LinkState.OFFLINE),
        ),
        transfers = listOf(
            t(1, Direction.OUT, "Скриншоты.zip", 842_000_000, 512_000_000, TransferState.ACTIVE, speed = 38_400_000),
            t(2, Direction.IN, "IMG_20261002_184011.jpg", 4_200_000, 0, TransferState.OFFERED, ask = true),
            t(3, Direction.IN, "voice-note.m4a", 1_800_000, 1_800_000, TransferState.DONE, res = "C:/x/voice-note.m4a"),
        ),
        texts = listOf(TextItem(9, "p1", "Nothing Phone (3a)", "https://nothing.tech/pages/essential-space", true, false, now - 90_000)),
        selectedId = "p1",
        saveDir = "C:\\Users\\Administrator\\Downloads\\Essential Share",
        message = "Clipboard sent",
    )
}

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "preview").also { it.mkdirs() }
    fun shot(name: String, ui: UiState) {
        val scene = ImageComposeScene(1060, 720, Density(1f)) { AppScreen(ui, object : Actions {}) }
        scene.render(0)
        val img = scene.render(1_000_000_000L)
        File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }
    ThemeState.mode = ThemeMode.DARK
    Lang.ru = false
    shot("en-dark", fakeState().copy(peers = fakeState().peers.take(2).map { it.copy(name = if (it.id == "p1") "Nothing Phone (3a)" else "Pixel 9") }, transfers = fakeState().transfers.map { it.copy(name = if (it.id == 1L) "Screenshots.zip" else if (it.id == 2L) "IMG_20261002.jpg" else "voice-note.m4a") }))
    shot("en-empty-dark", fakeState().copy(selectedId = null, peers = emptyList(), transfers = emptyList(), texts = emptyList()))
    Lang.ru = true
    shot("ru-dark", fakeState())
    shot("ru-settings-dark", fakeState().copy(settingsOpen = true))
    shot("ru-pair-dark", fakeState().copy(pairing = listOf(PairingRequest("p2", "Pixel 9", Platform.ANDROID, "482913"))))
    ThemeState.mode = ThemeMode.LIGHT
    Lang.ru = false
    shot("en-light", fakeState().copy(peers = fakeState().peers.take(2), transfers = emptyList(), texts = emptyList()))
    println("rendered to $out")
}
