package ec.gob.simertpi.simertpi_citizen_app

import android.app.Application

/** Clears account-bound notification presentation state on every cold process start. */
class SimertpiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SimertpiPushGate.clearAtProcessStart(this)
    }
}
