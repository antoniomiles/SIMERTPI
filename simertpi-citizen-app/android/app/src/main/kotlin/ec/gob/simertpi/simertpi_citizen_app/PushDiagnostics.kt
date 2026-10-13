package ec.gob.simertpi.simertpi_citizen_app
import android.content.Context
import android.util.Log

/** Fixed vocabulary only: no token, owner, vehicle, payload or provider exception text. */
internal object PushDiagnosticVocabulary {
    val routes = setOf("NATIVE_SERVICE", "FLUTTER_RECEIVER")
    val outcomes = setOf("RECEIVED", "PERMISSION_BLOCKED", "CHANNEL_BLOCKED", "ACCOUNT_GATE_BLOCKED",
        "EXPIRED", "DUPLICATE", "STALE_CONTRACT", "UNSUPPORTED_CONTENT", "PRESENTATION_REQUESTED",
        "PRESENTATION_FAILED", "DEDUP_STORE_FAILED")
    fun accepts(route: String, outcome: String) = route in routes && outcome in outcomes
}
internal object PushDiagnostics {
    @Synchronized fun record(context: Context, route: String, outcome: String) {
        if (!PushDiagnosticVocabulary.accepts(route, outcome)) return
        val prefs = context.getSharedPreferences("simertpi_push_diagnostics", Context.MODE_PRIVATE)
        val key = "$route.$outcome"
        prefs.edit().putLong(key, prefs.getLong(key, 0L) + 1).putLong("lastAt", System.currentTimeMillis()).apply()
        Log.i("SIMERTPI_PUSH", "route=$route outcome=$outcome")
    }
    fun snapshot(context: Context): Map<String, Long> =
        context.getSharedPreferences("simertpi_push_diagnostics", Context.MODE_PRIVATE).all
            .filterKeys { it == "lastAt" || PushDiagnosticVocabulary.routes.any { route -> PushDiagnosticVocabulary.outcomes.any { result -> it == "$route.$result" } } }
            .mapValues { (_, value) -> (value as? Long) ?: 0L }
}
