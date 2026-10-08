package ec.gob.simertpi.simertpi_citizen_app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private var pushChannel: MethodChannel? = null
    private var pendingPushTap: Map<String, String>? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("simertpi_updates", "Avisos SIMERTPI", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Avisos sobre tu estacionamiento y actividad SIMERTPI" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        pushChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "simertpi/notification_settings")
        pushChannel?.setMethodCallHandler { call, result ->
                when (call.method) {
                    "permissionStatus" -> result.success(permissionStatus())
                    "setPresentationGate" -> {
                        val args = call.arguments as? Map<*, *>
                        val owner = args?.get("ownerId") as? String
                        val enabled = args?.get("enabled") == true && !owner.isNullOrBlank()
                        val persisted = SimertpiPushGate.set(this, owner, enabled)
                        if (enabled && !persisted) result.error("GATE_PERSIST_FAILED", "Push presentation remains disabled", null)
                        else result.success(persisted)
                    }
                    "consumePushTap" -> result.success(consumePushTap())
                    "openNotificationSettings" -> {
                        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(android.net.Uri.parse("package:$packageName"))
                        startActivity(intent)
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
        pendingPushTap?.let { dispatchPushTap(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractPushTap(intent, clear = false)?.let { dispatchPushTap(it) }
    }

    private fun consumePushTap(): Map<String, String>? {
        pendingPushTap?.let {
            pendingPushTap = null
            clearPushExtras(intent)
            return it
        }
        return extractPushTap(intent, clear = true)
    }

    private fun dispatchPushTap(target: Map<String, String>) {
        val channel = pushChannel
        if (channel == null) {
            pendingPushTap = target
            return
        }
        channel.invokeMethod("pushOpened", target, object : MethodChannel.Result {
            override fun success(result: Any?) {
                if (result == true) {
                    pendingPushTap = null
                    clearPushExtras(intent)
                } else pendingPushTap = target
            }
            override fun error(code: String, message: String?, details: Any?) { pendingPushTap = target }
            override fun notImplemented() { pendingPushTap = target }
        })
    }

    private fun extractPushTap(source: Intent?, clear: Boolean): Map<String, String>? {
        source ?: return null
        val notificationId = source.getStringExtra("simertpi_push_notification_id") ?: return null
        val eventType = source.getStringExtra("simertpi_push_event_type") ?: return null
        val resourceType = source.getStringExtra("simertpi_push_resource_type") ?: return null
        val resourceId = source.getStringExtra("simertpi_push_resource_id") ?: return null
        val target = hashMapOf(
            "notificationId" to notificationId,
            "eventType" to eventType,
            "resourceType" to resourceType,
            "resourceId" to resourceId,
        )
        if (clear) clearPushExtras(source)
        return target
    }

    private fun clearPushExtras(source: Intent?) {
        source?.removeExtra("simertpi_push_notification_id")
        source?.removeExtra("simertpi_push_event_type")
        source?.removeExtra("simertpi_push_resource_type")
        source?.removeExtra("simertpi_push_resource_id")
    }

    private fun permissionStatus(): String {
        if (Build.VERSION.SDK_INT >= 33) {
            return if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) "granted" else "denied"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !manager.areNotificationsEnabled()) "denied" else "granted"
    }
}
