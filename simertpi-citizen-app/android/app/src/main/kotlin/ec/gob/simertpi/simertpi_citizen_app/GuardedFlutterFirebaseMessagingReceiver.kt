package ec.gob.simertpi.simertpi_citizen_app
import android.content.Context
import android.content.Intent
import com.google.firebase.messaging.RemoteMessage
import io.flutter.plugins.firebase.messaging.FlutterFirebaseMessagingReceiver

/** Preserve FlutterFire forwarding/badge updates; present data-only through the shared gate. */
class GuardedFlutterFirebaseMessagingReceiver : FlutterFirebaseMessagingReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Present before superclass goAsync: bounded native work remains inside receiver lifetime.
        intent.extras?.let { NativePushPresenter.present(context, RemoteMessage(it).data, "FLUTTER_RECEIVER") }
        super.onReceive(context, intent)
    }
}
