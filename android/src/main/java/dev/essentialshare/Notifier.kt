package dev.essentialshare

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.essentialshare.core.NodeEvent
import dev.essentialshare.core.TransferInfo

object Notifier {
    const val CH_STATUS = "status"
    const val CH_EVENTS = "events"
    const val ID_SERVICE = 1
    private const val ID_PAIR = 2
    private const val ID_BASE = 1000

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, tr("Ready to receive", "Готов к приёму"), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                description = tr("Keeps Essential Share reachable from your PC", "Чтобы ПК мог найти телефон и отправить файлы")
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_EVENTS, tr("Transfers", "Передачи"), NotificationManager.IMPORTANCE_HIGH).apply {
                description = tr("Incoming files, pairing requests and messages", "Входящие файлы, запросы на сопряжение и сообщения")
            },
        )
    }

    private fun canNotify(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun open(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(ctx: Context, what: String, id: Long, label: String, req: Int): NotificationCompat.Action {
        val i = Intent(ctx, ActionReceiver::class.java).setAction(what).putExtra("id", id)
        val pi = PendingIntent.getBroadcast(ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Action.Builder(0, label, pi).build()
    }

    private fun builder(ctx: Context) = NotificationCompat.Builder(ctx, CH_EVENTS)
        .setSmallIcon(R.drawable.ic_stat_share).setColor(0xFFD71921.toInt()).setAutoCancel(true).setContentIntent(open(ctx))
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)

    fun serviceNotification(ctx: Context, text: String, progress: Int? = null): Notification =
        NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_share)
            .setContentTitle("Essential Share")
            .setContentText(text)
            .setContentIntent(open(ctx))
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { if (progress != null) setProgress(100, progress, false) }
            .build()

    fun onEvent(ctx: Context, e: NodeEvent) {
        if (!canNotify(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val visible = Rt.uiVisible.value > 0
        when (e) {
            is NodeEvent.Pairing -> if (!visible) nm.notify(
                ID_PAIR,
                builder(ctx).setContentTitle(tr("Pairing request", "Запрос на сопряжение")).setContentText(e.request.peerName + " · " + e.request.code.chunked(3).joinToString(" "))
                    .setPriority(NotificationCompat.PRIORITY_HIGH).build(),
            )
            is NodeEvent.Offer -> if (!visible) {
                val t = e.transfer
                nm.notify(
                    ID_BASE + (t.id and 0xffff).toInt(),
                    builder(ctx).setContentTitle(t.peerName + tr(" wants to send", " хочет отправить")).setContentText("${t.name} · ${formatSize(t.size)}")
                        .addAction(action(ctx, "accept", t.id, tr("Accept", "Принять"), (t.id and 0xffff).toInt() * 2))
                        .addAction(action(ctx, "decline", t.id, tr("Decline", "Отклонить"), (t.id and 0xffff).toInt() * 2 + 1))
                        .setPriority(NotificationCompat.PRIORITY_HIGH).build(),
                )
            }
            is NodeEvent.FileReceived -> if (!visible) fileReceived(ctx, nm, e.transfer)
            is NodeEvent.TextReceived -> {
                if (e.item.clipboard && Rt.applyClipboard) Rt.setClipboard(ctx, e.item.text)
                if (!visible) nm.notify(
                    ID_BASE + 70000 + (e.item.id and 0xffff).toInt(),
                    builder(ctx).setContentTitle(e.item.peerName + if (e.item.clipboard) tr(" · clipboard", " · буфер обмена") else "")
                        .setContentText(e.item.text).setStyle(NotificationCompat.BigTextStyle().bigText(e.item.text)).build(),
                )
            }
            is NodeEvent.TransferFailed -> if (!visible) nm.notify(
                ID_BASE + (e.transfer.id and 0xffff).toInt(),
                builder(ctx).setContentTitle(tr("Transfer failed", "Передача не удалась")).setContentText(e.transfer.name + (e.transfer.error?.let { " · $it" } ?: "")).build(),
            )
            is NodeEvent.Paired -> {}
        }
    }

    private fun fileReceived(ctx: Context, nm: NotificationManager, t: TransferInfo) {
        val b = builder(ctx).setContentTitle(tr("Received from ", "Получено от ") + t.peerName).setContentText(t.name)
        t.result?.let { r ->
            val uri = Uri.parse(r)
            // our own tiny activity opens it, so APKs can go through the install permission check first
            val view = Intent(ctx, OpenFileActivity::class.java).setData(uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            b.setContentIntent(PendingIntent.getActivity(ctx, (t.id and 0xffff).toInt(), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        nm.notify(ID_BASE + (t.id and 0xffff).toInt(), b.build())
    }

    fun cancel(ctx: Context, transferId: Long) =
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_BASE + (transferId and 0xffff).toInt())
}

/** Accept / decline buttons on the incoming-file notification. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", 0)
        Rt.acquire()
        Rt.node.answerOffer(id, intent.action == "accept")
        Notifier.cancel(context, id)
        Rt.release()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Rt.alwaysReady) runCatching { ShareService.start(context) }
    }
}
