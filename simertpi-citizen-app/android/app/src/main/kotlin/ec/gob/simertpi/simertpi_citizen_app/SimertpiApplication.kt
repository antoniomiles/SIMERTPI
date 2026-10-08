package ec.gob.simertpi.simertpi_citizen_app

import android.app.Application

/** Restores only a consented, backend-registered device authorization. */
class SimertpiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SimertpiPushGate.restoreAtProcessStart(this)
    }
}
