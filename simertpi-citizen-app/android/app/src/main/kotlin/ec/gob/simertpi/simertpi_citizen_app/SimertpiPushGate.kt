package ec.gob.simertpi.simertpi_citizen_app

import android.content.Context
import android.content.SharedPreferences

internal object SimertpiPushGate {
    private const val PREFS = "simertpi_push_presentation"
    private const val OWNER = "owner_id"
    private const val ENABLED = "enabled"
    @Volatile private var processGateEnabled = false

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun set(context: Context, ownerId: String?, enabled: Boolean): Boolean {
        if (!enabled) processGateEnabled = false
        val editor = preferences(context).edit()
        if (enabled && !ownerId.isNullOrBlank()) {
            editor.putString(OWNER, ownerId).putBoolean(ENABLED, true)
        } else {
            editor.remove(OWNER).putBoolean(ENABLED, false)
        }
        // commit() is intentional: logout must persist the closed gate before
        // clearing credentials or attempting network/Firebase cleanup.
        val saved = editor.commit()
        processGateEnabled = saved && enabled && !ownerId.isNullOrBlank()
        if (!enabled) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancelAll()
        }
        return saved
    }

    fun allows(context: Context, data: Map<String, String>): Boolean {
        val prefs = preferences(context)
        val activeOwner = prefs.getString(OWNER, null)
        return PushPresentationPolicy.allows(
            processGateEnabled && prefs.getBoolean(ENABLED, false), activeOwner, data,
        )
    }

    fun clearAtProcessStart(context: Context) {
        processGateEnabled = false
        set(context, null, false)
    }
}

internal object PushPresentationPolicy {
    private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
    private val events = setOf("PARKING_ENDING_SOON", "PARKING_EXTENSION_CONFIRMED")

    fun valid(data: Map<String, String>): Boolean =
        data["notificationId"]?.matches(uuid) == true &&
            data["eventType"] in events &&
            data["resourceType"] == "PARKING_SESSION" &&
            data["resourceId"]?.matches(uuid) == true

    fun allows(enabled: Boolean, activeOwner: String?, data: Map<String, String>): Boolean =
        enabled && !activeOwner.isNullOrBlank() &&
            activeOwner == data["recipientOwnerId"] && valid(data)
}
