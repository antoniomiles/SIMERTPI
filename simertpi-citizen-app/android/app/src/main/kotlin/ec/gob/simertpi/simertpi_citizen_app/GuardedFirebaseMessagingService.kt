package ec.gob.simertpi.simertpi_citizen_app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.RemoteMessage
import io.flutter.plugins.firebase.messaging.FlutterFirebaseMessagingService

/** FCM data-only receiver. It never displays a message without the local account gate. */
class GuardedFirebaseMessagingService : FlutterFirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (!SimertpiPushGate.allows(this, data)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return

        ensureChannel()
        val eventType = data.getValue("eventType")
        val copy = PushEventCopy.forEvent(eventType) ?: return
        val notificationId = data.getValue("notificationId")
        val tap = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("simertpi_push_notification_id", notificationId)
            putExtra("simertpi_push_event_type", data.getValue("eventType"))
            putExtra("simertpi_push_resource_type", data.getValue("resourceType"))
            putExtra("simertpi_push_resource_id", data.getValue("resourceId"))
        }
        val pending = PendingIntent.getActivity(
            this, notificationId.hashCode(), tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(copy.first)
            .setContentText(copy.second)
            .setStyle(NotificationCompat.BigTextStyle().bigText(copy.second))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(applicationInfo.icon)
                .setContentTitle("SIMERTPI")
                .setContentText("Tienes una actualización en SIMERTPI")
                .build())
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(notificationId.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission may be revoked between the check and notify.
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, "Avisos SIMERTPI", NotificationManager.IMPORTANCE_DEFAULT)
            channel.description = "Avisos sobre tu estacionamiento y actividad SIMERTPI"
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL = "simertpi_updates"
    }
}


internal object PushEventCopy {
    fun forEvent(eventType: String): Pair<String, String>? = when (eventType) {
        "PARKING_STARTED" -> "Estacionamiento iniciado" to "Tu estacionamiento está activo."
        "PARKING_ENDING_SOON" -> "Próximo a vencer" to "Tu tiempo está próximo a finalizar."
        "PARKING_TIME_EXPIRED" -> "Tiempo finalizado" to "El tiempo contratado ha finalizado."
        "PARKING_EXTENSION_CONFIRMED" -> "Extensión confirmada" to "Tu extensión fue confirmada."
        "PARKING_COMPLETED" -> "Estacionamiento finalizado" to "Tu estacionamiento fue finalizado correctamente."
        else -> null
    }
}
