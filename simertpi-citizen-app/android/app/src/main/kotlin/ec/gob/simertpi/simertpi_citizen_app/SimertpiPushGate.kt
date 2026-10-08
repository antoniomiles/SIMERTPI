package ec.gob.simertpi.simertpi_citizen_app

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Account-bound, consent-scoped native presentation gate for data-only FCM. */
internal object SimertpiPushGate {
    private const val PREFS = "simertpi_push_presentation"
    private const val OWNER = "owner_id"
    private const val DEVICE = "backend_device_id"
    private const val CONSENT = "explicit_consent"
    private const val REGISTERED = "backend_registration_confirmed"
    private const val ENABLED = "enabled"
    @Volatile private var processGateEnabled = false

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Opens the persisted gate only after an authenticated backend registration. */
    fun set(
        context: Context,
        ownerId: String?,
        backendDeviceId: String?,
        consentGranted: Boolean,
        enabled: Boolean,
    ): Boolean {
        processGateEnabled = false
        val prefs = preferences(context)
        if (!enabled) {
            val saved = prefs.edit().clear().commit()
            NotificationManagerCompat.from(context).cancelAll()
            return saved
        }

        val authorization = PushAuthorization(
            ownerId = ownerId,
            backendDeviceId = backendDeviceId,
            consentGranted = consentGranted,
            backendRegistrationConfirmed = true,
            enabled = true,
        )
        if (!PushPresentationPolicy.hasCompleteAuthorization(authorization) ||
            !PushPresentationPolicy.canRestore(authorization, notificationsAllowed(context))) {
            prefs.edit().clear().commit()
            return false
        }

        // Clear first so a failed write cannot leave an older account authorized.
        if (!prefs.edit().clear().commit()) return false
        val saved = prefs.edit()
            .putString(OWNER, ownerId)
            .putString(DEVICE, backendDeviceId)
            .putBoolean(CONSENT, true)
            .putBoolean(REGISTERED, true)
            .putBoolean(ENABLED, true)
            .commit()
        if (!saved) {
            prefs.edit().clear().commit()
            return false
        }
        processGateEnabled = true
        return true
    }

    /** Called for every process start, including FCM's cold-start service launch. */
    fun restoreAtProcessStart(context: Context): Boolean {
        processGateEnabled = false
        val prefs = preferences(context)
        val authorization = PushAuthorization(
            ownerId = prefs.getString(OWNER, null),
            backendDeviceId = prefs.getString(DEVICE, null),
            consentGranted = prefs.getBoolean(CONSENT, false),
            backendRegistrationConfirmed = prefs.getBoolean(REGISTERED, false),
            enabled = prefs.getBoolean(ENABLED, false),
        )
        if (!PushPresentationPolicy.hasCompleteAuthorization(authorization)) {
            prefs.edit().clear().commit()
            return false
        }
        // Keep consent/registration for a later app resume, but fail closed now.
        if (!PushPresentationPolicy.canRestore(authorization, notificationsAllowed(context))) return false
        processGateEnabled = true
        return true
    }

    fun allows(context: Context, data: Map<String, String>): Boolean {
        val prefs = preferences(context)
        val activeOwner = prefs.getString(OWNER, null)
        val validPersistedAuthorization = PushPresentationPolicy.canRestore(
            PushAuthorization(
                ownerId = activeOwner,
                backendDeviceId = prefs.getString(DEVICE, null),
                consentGranted = prefs.getBoolean(CONSENT, false),
                backendRegistrationConfirmed = prefs.getBoolean(REGISTERED, false),
                enabled = prefs.getBoolean(ENABLED, false),
            ),
            notificationsAllowed(context),
        )
        return processGateEnabled && validPersistedAuthorization &&
            notificationsAllowed(context) &&
            PushPresentationPolicy.allows(true, activeOwner, data)
    }

    private fun notificationsAllowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
}

internal data class PushAuthorization(
    val ownerId: String?,
    val backendDeviceId: String?,
    val consentGranted: Boolean,
    val backendRegistrationConfirmed: Boolean,
    val enabled: Boolean,
)

internal object PushPresentationPolicy {
    private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
    private val events = setOf("PARKING_STARTED", "PARKING_ENDING_SOON", "PARKING_TIME_EXPIRED", "PARKING_EXTENSION_CONFIRMED", "PARKING_COMPLETED")

    fun hasCompleteAuthorization(authorization: PushAuthorization): Boolean =
        authorization.enabled && authorization.consentGranted &&
            authorization.backendRegistrationConfirmed &&
            !authorization.ownerId.isNullOrBlank() &&
            authorization.backendDeviceId?.matches(uuid) == true

    fun canRestore(authorization: PushAuthorization, notificationsAllowed: Boolean): Boolean =
        notificationsAllowed && hasCompleteAuthorization(authorization)

    fun valid(data: Map<String, String>): Boolean =
        data["notificationId"]?.matches(uuid) == true &&
            data["eventType"] in events &&
            data["resourceType"] == "PARKING_SESSION" &&
            data["resourceId"]?.matches(uuid) == true

    fun allows(enabled: Boolean, activeOwner: String?, data: Map<String, String>): Boolean =
        enabled && !activeOwner.isNullOrBlank() &&
            activeOwner == data["recipientOwnerId"] && valid(data)
}
