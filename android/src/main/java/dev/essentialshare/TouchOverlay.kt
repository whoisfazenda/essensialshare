package dev.essentialshare

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.essentialshare.core.NodeEvent
import dev.essentialshare.ui.BtnKind
import dev.essentialshare.ui.N
import dev.essentialshare.ui.NButton
import dev.essentialshare.ui.NMeta
import dev.essentialshare.ui.NText
import dev.essentialshare.ui.NType
import dev.essentialshare.ui.PairDialog
import dev.essentialshare.ui.nCard

/** How many touch screens the app itself has open right now (main thread). */
object TouchState {
    var screens = 0
}

private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    init {
        controller.performAttach()
        controller.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}

/**
 * The receiving side of a touch when the app is closed: the other phone says it was touched to this one, and the
 * same animation appears as a window over whatever is on screen. It needs the "display over other apps" switch;
 * without it the usual notifications still tell about the files.
 */
object TouchOverlay {
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null

    fun allowed(ctx: Context) = Settings.canDrawOverlays(ctx)

    private var shownAt = 0L

    /** Whether a window may go up now; a window that has outlived any transfer is taken down first. */
    private fun canShow(ctx: Context, what: String): Boolean {
        if (view != null && System.currentTimeMillis() - shownAt > 90_000) dismiss(ctx)
        val ok = allowed(ctx) && TouchState.screens == 0 && view == null
        Log.d("EssentialShare", "touch overlay ($what): allowed=${allowed(ctx)} screens=${TouchState.screens} showing=${view != null} -> $ok")
        return ok
    }

    fun onEvent(ctx: Context, e: NodeEvent) {
        if (e !is NodeEvent.Touched) return
        if (canShow(ctx, "touched")) show(ctx, e.peerId, awaitLink = false)
    }

    /** A phone is opening a direct Wi-Fi link to this one: the animation starts now and the link follows. */
    fun showAwaiting(ctx: Context, senderPrefix: String) {
        if (canShow(ctx, "direct request")) show(ctx, senderPrefix, awaitLink = true)
    }

    private fun show(ctx: Context, peerId: String, awaitLink: Boolean) {
        shownAt = System.currentTimeMillis()
        val app = ctx.applicationContext
        val wm = app.getSystemService(WindowManager::class.java)
        val o = OverlayOwner()
        val v = ComposeView(app).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setViewTreeViewModelStoreOwner(o)
            keepScreenOn = true
            setContent {
                EssentialTheme {
                    val pairing by Rt.node.pairing.collectAsState()
                    Box(Modifier.fillMaxSize().background(N.bg).padding(top = 24.dp, bottom = 12.dp)) {
                        TouchScreen(emptyList(), null, canPick = false, passive = true, attachPeer = peerId, awaitLink = awaitLink, onClose = { dismiss(app) })
                        pairing.firstOrNull()?.let { req -> PairDialog(req) { ok -> Rt.node.answerPairing(req.peerId, ok) } }
                    }
                }
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // really the whole screen: under the status bar, the camera cut-out and the navigation bar
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        }
        runCatching { wm.addView(v, lp) }.onFailure { o.destroy(); return }
        view = v; owner = o
        Rt.uiStarted()
    }

    fun dismiss(ctx: Context) {
        val v = view ?: return
        view = null
        runCatching { ctx.applicationContext.getSystemService(WindowManager::class.java).removeView(v) }
        owner?.destroy(); owner = null
        Rt.uiStopped()
    }
}

/** Asks for the "display over other apps" switch, which the closed-app animation needs. Hidden once it is on. */
@Composable
fun OverlayPermissionRow(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var ok by remember { mutableStateOf(TouchOverlay.allowed(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ok = TouchOverlay.allowed(ctx) }
    if (ok) return
    Row(
        modifier.fillMaxWidth().nCard(24.dp).padding(start = 18.dp, top = 12.dp, bottom = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            NText(tr("Receive with the app closed", "Приём при закрытом приложении"), style = NType.bodyMedium, maxLines = 2)
            NMeta(tr("Allow display over other apps to see the animation", "Разрешите показ поверх других приложений, чтобы видеть анимацию"), maxLines = 3)
        }
        NButton(tr("Allow", "Разрешить"), {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }, kind = BtnKind.FILLED)
    }
}
