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

/** Both transport routes share a single synchronized native presenter. */
class GuardedFirebaseMessagingService : FlutterFirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        NativePushPresenter.present(this, message.data, "NATIVE_SERVICE")
    }
}

internal object NativePushPresenter {
    const val CHANNEL = "simertpi_updates"
    @Synchronized fun present(context: Context, data: Map<String, String>, route: String) {
        PushDiagnostics.record(context, route, "RECEIVED")
        ensureChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val permissionAllowed = (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        val channelAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        val authorization = SimertpiPushGate.allows(context, data)
        val owner = data["recipientOwnerId"] ?: ""
        val prefs = context.getSharedPreferences("simertpi_push_seen", Context.MODE_PRIVATE)
        val seenKey = "$owner:${data["notificationId"]}"
        val contractKey = "contract:$owner:${data["resourceId"]}"
        val previousEnd = prefs.getLong(contractKey, 0L)
        val reason = PushPresentationChecks.rejection(
            permissionAllowed, channelAllowed, authorization,
            PushDeliveryPolicy.fresh(data, System.currentTimeMillis()),
            prefs.contains(seenKey), PushDeliveryPolicy.currentContract(data, previousEnd),
        )
        if (reason != null) { PushDiagnostics.record(context, route, reason); return }
        val copy = PushEventCopy.forEvent(data.getValue("eventType"), data)
        if (copy == null) { PushDiagnostics.record(context, route, "UNSUPPORTED_CONTENT"); return }
        val notificationId = data.getValue("notificationId")
        val tap = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("simertpi_push_notification_id", notificationId)
            putExtra("simertpi_push_event_type", data.getValue("eventType"))
            putExtra("simertpi_push_resource_type", data.getValue("resourceType"))
            putExtra("simertpi_push_resource_id", data.getValue("resourceId"))
        }
        val pending = PendingIntent.getActivity(context, notificationId.hashCode(), tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(copy.first).setContentText(copy.second)
            .setStyle(NotificationCompat.BigTextStyle().bigText(copy.second))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle("SIMERTPI")
                .setContentText("Tienes una actualizaci\u00f3n en SIMERTPI").build())
            .setContentIntent(pending).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT).build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId, 0, notification)
            // notify returning is a request to Android, never proof that the user saw it.
            PushDiagnostics.record(context, route, "PRESENTATION_REQUESTED")
            val edit = prefs.edit()
            prefs.all.entries.sortedBy { (it.value as? Long) ?: 0L }
                .take((prefs.all.size - 255).coerceAtLeast(0)).forEach { edit.remove(it.key) }
            data["contractEndEpoch"]?.toLongOrNull()?.let { edit.putLong(contractKey, maxOf(previousEnd, it)) }
            if (!edit.putLong(seenKey, System.currentTimeMillis()).commit())
                PushDiagnostics.record(context, route, "DEDUP_STORE_FAILED")
        } catch (_: SecurityException) {
            PushDiagnostics.record(context, route, "PERMISSION_BLOCKED")
        } catch (_: RuntimeException) {
            PushDiagnostics.record(context, route, "PRESENTATION_FAILED")
        }
    }
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, "Avisos SIMERTPI", NotificationManager.IMPORTANCE_DEFAULT)
            channel.description = "Avisos sobre tu estacionamiento y actividad SIMERTPI"
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }
}

/** Pure decision contract shared by the real presenter and JVM tests. */
internal object PushPresentationChecks {
    fun rejection(permission: Boolean, channel: Boolean, authorized: Boolean,
        fresh: Boolean, duplicate: Boolean, currentContract: Boolean): String? = when {
        !permission -> "PERMISSION_BLOCKED"
        !channel -> "CHANNEL_BLOCKED"
        !authorized -> "ACCOUNT_GATE_BLOCKED"
        !fresh -> "EXPIRED"
        duplicate -> "DUPLICATE"
        !currentContract -> "STALE_CONTRACT"
        else -> null
    }
}


internal object PushEventCopy {
    fun forEvent(eventType: String, data: Map<String, String> = emptyMap()): Pair<String, String>? = when (eventType) {
        "VEHICLE_REGISTERED" -> safeLabel(data["plate"])?.let { "Vehículo registrado" to "Tu vehículo con placa $it fue registrado correctamente." }
        "VEHICLE_DELETED" -> safeLabel(data["plate"])?.let { "Vehículo eliminado" to "El vehículo con placa $it fue eliminado de tu cuenta." }
        "PARKING_STARTED" -> "Estacionamiento iniciado" to details(data, started(data), true)
        "PARKING_ENDING_SOON" -> "Próximo a vencer" to details(data, endingSoon(data), false)
        "PARKING_TIME_EXPIRED" -> "Tiempo finalizado" to details(data, expired(data), true)
        "PARKING_EXTENSION_CONFIRMED" -> "Extensión confirmada" to details(data, extension(data), false)
        "PARKING_COMPLETED" -> "Estacionamiento finalizado" to completed(data)
        else -> null
    }

    private fun details(data: Map<String,String>, fallback: String, space: Boolean): String {
        val fields = mutableListOf(fallback)
        safeLabel(data["plate"])?.let { fields.add("Vehículo: $it.") }
        if(space) safeLabel(data["spaceCode"])?.let { fields.add("Espacio: $it.") }
        if(data["eventType"] == "PARKING_STARTED") safeDate(data["startedAt"])?.let { fields.add("Inicio: $it.") }
        safeDate(data["expectedEndAt"])?.let { fields.add("Finalización: $it (Ecuador).") }
        if(data["eventType"] == "PARKING_EXTENSION_CONFIRMED") {
            val amount = data["amount"]?.takeIf { it.matches(Regex("^[0-9]{1,10}(\\.[0-9]{1,2})?$")) }
            val currency = data["currency"]?.takeIf { it.matches(Regex("^[A-Z]{3}$")) }
            if(amount != null && currency != null) fields.add("Pago aprobado: $amount $currency.")
        }
        return fields.joinToString(" ")
    }
    private fun safeLabel(value: String?): String? = value?.takeIf {
        it.length in 1..40 && it.matches(Regex("^[A-Za-z0-9 -]+$"))
    }
    private fun safeDate(value: String?): String? = value?.takeIf {
        it.matches(Regex("^[0-9]{2}/[0-9]{2}/[0-9]{4} [0-9]{2}:[0-9]{2}$"))
    }

    private fun started(data: Map<String, String>): String {
        val minutes = data["contractedMinutes"]?.toIntOrNull()?.takeIf { it in 1..240 }
        val time = safeTime(data["expectedEndTime"])
        return if (minutes != null && time != null)
            "Tu estacionamiento se inició correctamente por $minutes minutos. Finaliza a las $time."
        else "Tu estacionamiento se inició correctamente."
    }

    private fun endingSoon(data: Map<String, String>): String {
        val minutes = data["minutesRemaining"]?.toIntOrNull()?.takeIf { it in 1..240 }
        return if (minutes != null) "Quedan $minutes minutos para que finalice tu estacionamiento."
        else "Tu tiempo está próximo a finalizar."
    }

    private fun expired(data: Map<String, String>): String = safeTime(data["expectedEndTime"])
        ?.let { "El tiempo contratado finalizó a las $it. Revisa el estado de tu estacionamiento." }
        ?: "El tiempo contratado ha finalizado."

    private fun extension(data: Map<String, String>): String {
        val minutes = data["additionalMinutes"]?.toIntOrNull()?.takeIf { it in 1..240 }
        val time = safeTime(data["expectedEndTime"])
        return when {
            minutes != null && time != null -> "Tu estacionamiento se extendió correctamente por $minutes minutos. Nuevo vencimiento: $time."
            minutes != null -> "Tu estacionamiento se extendió correctamente por $minutes minutos."
            else -> "Tu extensión fue confirmada."
        }
    }

    private fun completed(data: Map<String, String>): String = safeTime(data["endedAt"])
        ?.let { "Tu estacionamiento finalizó a las $it." }
        ?: "Tu estacionamiento ha finalizado."

    private fun safeTime(value: String?): String? = value?.takeIf {
        it.matches(Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$"))
    }
}

/** Missing expiry is accepted only for historical backend payload compatibility. */
internal object PushDeliveryPolicy {
    fun currentContract(data: Map<String,String>, latestEnd: Long): Boolean {
        if(data["eventType"] !in setOf("PARKING_ENDING_SOON","PARKING_TIME_EXPIRED")) return true
        val end=data["contractEndEpoch"]?.toLongOrNull() ?: return latestEnd == 0L
        return end >= latestEnd
    }
    fun fresh(data: Map<String,String>, now: Long): Boolean {
        val expiry = data["validUntil"] ?: return true
        return expiry.toLongOrNull()?.let { it > now } ?: false
    }
}
