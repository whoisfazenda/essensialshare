package dev.essentialshare

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import dev.essentialshare.core.LinkState
import dev.essentialshare.core.TransferState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Keeps the node alive in the background so the PC can see this phone and push files at any time. */
class ShareService : Service() {
    private var job: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastUpdate = 0L
    private var lastText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.channels(this)
        Rt.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(Notifier.ID_SERVICE, Notifier.serviceNotification(this, tr("Ready to receive", "Готов к приёму")), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (job == null) {
            job = Rt.scope.launch {
                combine(Rt.node.transfers, Rt.node.peers) { t, p -> t to p }.collect { (transfers, peers) ->
                    val active = transfers.filter { it.state == TransferState.ACTIVE }
                    setBoost(active.isNotEmpty())
                    val now = System.currentTimeMillis()
                    val text: String
                    var progress: Int? = null
                    if (active.isNotEmpty()) {
                        val total = active.sumOf { it.size }.coerceAtLeast(1)
                        val done = active.sumOf { it.transferred }
                        progress = (done * 100 / total).toInt()
                        val incoming = active.first().direction == dev.essentialshare.core.Direction.IN
                        text = (if (incoming) tr("Receiving ", "Приём ") else tr("Sending ", "Отправка ")) + active.first().name +
                            (if (active.size > 1) " +${active.size - 1}" else "") + " · $progress%"
                    } else {
                        val n = peers.count { it.state == LinkState.READY }
                        text = if (n > 0) tr("Connected: $n", "Подключено: $n") else tr("Ready to receive", "Готов к приёму")
                    }
                    if (text != lastText && (now - lastUpdate > 700 || progress == null)) {
                        lastText = text; lastUpdate = now
                        getSystemService(NotificationManager::class.java).notify(Notifier.ID_SERVICE, Notifier.serviceNotification(this@ShareService, text, progress))
                    }
                }
            }
        }
        return START_STICKY
    }

    /** High-performance Wi-Fi and a CPU wake lock only while bytes are moving. */
    private fun setBoost(on: Boolean) {
        if (on) {
            if (wifiLock == null) {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "essential-share").apply { setReferenceCounted(false); acquire() }
            }
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "essential-share:transfer").apply { setReferenceCounted(false); acquire(60 * 60 * 1000L) }
            }
        } else {
            runCatching { wifiLock?.release() }; wifiLock = null
            runCatching { wakeLock?.release() }; wakeLock = null
        }
    }

    override fun onDestroy() {
        job?.cancel()
        setBoost(false)
        Rt.release()
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ShareService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ShareService::class.java))
        }
    }
}

class ShareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Rt.init(this)
        Notifier.channels(this)
    }
}
