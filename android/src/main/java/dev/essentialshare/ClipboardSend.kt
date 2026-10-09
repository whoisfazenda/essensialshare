package dev.essentialshare

import android.app.Activity
import android.app.PendingIntent
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.service.quicksettings.TileService
import android.widget.Toast
import dev.essentialshare.core.LinkState

/** Quick Settings tile: send whatever is on the clipboard to the connected PC. */
class ClipboardTileService : TileService() {
    override fun onClick() {
        val i = Intent(this, ClipboardSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
    }
}

/**
 * Android only lets the focused app read the clipboard, so this invisible activity takes focus for a moment,
 * reads it, pushes it to every connected device and closes.
 */
class ClipboardSendActivity : Activity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Rt.acquire()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val text = getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()
        val ready = Rt.node.peers.value.filter { it.state == LinkState.READY }
        val msg = when {
            text.isNullOrBlank() -> tr("Clipboard is empty", "Буфер обмена пуст")
            ready.isEmpty() -> tr("No connected device", "Нет подключённых устройств")
            else -> { Rt.node.sendClipboardToAll(text); tr("Clipboard sent", "Буфер отправлен") + " · " + ready.joinToString { it.name } }
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        Rt.release()
        super.onDestroy()
    }
}

/** Opens a received file from a notification; invisible, it hands over to [openFile] and closes. */
class OpenFileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openFile(this, intent?.data?.toString())
        finish()
    }
}
