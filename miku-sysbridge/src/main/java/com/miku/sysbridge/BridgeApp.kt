package com.miku.sysbridge

import android.app.Application

/**
 * The bridge runs as a persistent app (see the manifest) so the charge limit and the CPU power profile can follow the
 * battery. Everything else here is still on-demand broadcast receivers.
 */
class BridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ChargeLimiter.start(this)
        PowerProfiles.start(this)
    }
}
