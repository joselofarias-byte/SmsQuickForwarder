package com.oncet.smsquickforwarder.failure

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.oncet.smsquickforwarder.LogDetailActivity
import com.oncet.smsquickforwarder.R
import com.oncet.smsquickforwarder.data.ForwardLogStore
import com.oncet.smsquickforwarder.data.SettingsStore

object FailureNotifier {
    private const val PREF = "failure_notifications"
    private const val KEY_NOTIFIED = "notified_event_ids"
    private const val CHANNEL_ID = "forward_failure_notifications"

    fun notifyIfNeeded(context: Context, eventId: String?) {
        if (eventId.isNullOrBlank()) return
        val obj = ForwardLogStore.eventById(context, eventId) ?: return
        val notified = notifiedIds(context)
        if (!FailureNotificationPolicy.shouldNotify(
                eventId = eventId,
                eventType = obj.optString("eventType"),
                decision = obj.optString("decision"),
                alreadyNotified = notified,
                enabled = SettingsStore.failureNotificationsEnabled(context)
            )
        ) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Reenvío fallido", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val count = recentFailureCount(context)
        val title = "Falló el reenvío del SMS"
        val text = if (FailureNotificationPolicy.shouldMergeRecentFailures(count)) {
            "Hubo $count reenvíos fallidos en los últimos 10 minutos. Tocá para ver detalles."
        } else {
            "${obj.optString("receivedAt").takeLast(8)} No se pudo reenviar el SMS. Tocá para ver detalles."
        }
        val intent = Intent(context, LogDetailActivity::class.java).putExtra(LogDetailActivity.EXTRA_EVENT_ID, eventId)
        val pending = PendingIntent.getActivity(context, eventId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(3001, android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build())
        markNotified(context, eventId)
    }

    private fun notifiedIds(context: Context): Set<String> =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getStringSet(KEY_NOTIFIED, emptySet()) ?: emptySet()

    private fun markNotified(context: Context, eventId: String) {
        val next = notifiedIds(context).toMutableList().apply { add(eventId) }.takeLast(100).toSet()
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putStringSet(KEY_NOTIFIED, next).apply()
    }

    private fun recentFailureCount(context: Context): Int =
        ForwardLogStore.searchUserEvents(context, "", "failed", "today", 20).length()
}
