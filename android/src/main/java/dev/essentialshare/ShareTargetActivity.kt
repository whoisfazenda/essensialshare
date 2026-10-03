package dev.essentialshare

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.essentialshare.core.LinkState
import dev.essentialshare.core.OutgoingFile
import dev.essentialshare.ui.DeviceCard
import dev.essentialshare.ui.Ic
import dev.essentialshare.ui.N
import dev.essentialshare.ui.NCaps
import dev.essentialshare.ui.NIconButton
import dev.essentialshare.ui.NMeta
import dev.essentialshare.ui.NText
import dev.essentialshare.ui.NType
import dev.essentialshare.ui.PairDialog
import dev.essentialshare.ui.nCard
import kotlin.concurrent.thread

/** Entry in the system share sheet: shows the nearby devices over the current app and sends on tap. */
class ShareTargetActivity : ComponentActivity() {
    private var files: List<OutgoingFile> = emptyList()
    private var text: String? = null
    private var prepared = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // open every stream now: the grant from the share intent may be gone once this activity finishes
        thread {
            val intent = intent
            val uris: List<Uri> = when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(intent.parcelable(Intent.EXTRA_STREAM))
                Intent.ACTION_SEND_MULTIPLE -> intent.parcelableList(Intent.EXTRA_STREAM)
                else -> emptyList()
            }
            files = uris.mapNotNull { UriFile.from(this, it, intent.type) }
            if (files.isEmpty()) text = intent.getStringExtra(Intent.EXTRA_TEXT)
            prepared = true
        }
        setContent { EssentialTheme { Sheet() } }
    }

    override fun onStart() { super.onStart(); Rt.uiStarted() }
    override fun onStop() { Rt.uiStopped(); super.onStop() }

    @Composable
    private fun Sheet() {
        val peers by Rt.node.peers.collectAsState()
        val pairing by Rt.node.pairing.collectAsState()
        var target by remember { mutableStateOf<String?>(null) }
        val usable = peers.filter { it.state != LinkState.OFFLINE }
        val count = if (files.isNotEmpty()) files.size else 1

        // pairing finished: the queued send runs by itself, so the sheet can go
        val t = target
        if (t != null && pairing.isEmpty() && peers.firstOrNull { it.id == t }?.trusted == true) finish()

        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(N.surface)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .navigationBarsPadding().padding(horizontal = 20.dp).padding(top = 22.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        NCaps(tr("Essential Share · $count item(s)", "Essential Share · элементов: $count"))
                        NText(tr("Send to", "Отправить на"), style = NType.headline)
                    }
                    NIconButton(Ic.CLOSE, { finish() }, tint = N.secondary)
                }
                if (usable.isEmpty()) {
                    Column(Modifier.fillMaxWidth().nCard(20.dp, N.surfaceRaised).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        NText(tr("Looking for devices…", "Ищем устройства…"), style = NType.bodyMedium)
                        NMeta(tr("Open Essential Share on your PC, same Wi-Fi.", "Откройте Essential Share на ПК, тот же Wi-Fi."), maxLines = 3)
                    }
                }
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(usable, key = { it.id }) { p ->
                        DeviceCard(p, p.id == target) {
                            if (target != null) return@DeviceCard
                            if (!prepared) return@DeviceCard
                            target = p.id
                            if (files.isNotEmpty()) Rt.node.sendFiles(p.id, files) else text?.let { Rt.node.sendText(p.id, it) }
                            if (p.trusted) finish()
                        }
                    }
                }
            }
        }
        pairing.firstOrNull()?.let { req ->
            PairDialog(req) { ok -> Rt.node.answerPairing(req.peerId, ok); if (!ok) finish() }
        }
    }
}

@Suppress("DEPRECATION")
private fun Intent.parcelable(key: String): Uri? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Uri::class.java) else getParcelableExtra(key) as? Uri

@Suppress("DEPRECATION")
private fun Intent.parcelableList(key: String): List<Uri> =
    (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, Uri::class.java) else getParcelableArrayListExtra<Uri>(key)) ?: emptyList()
