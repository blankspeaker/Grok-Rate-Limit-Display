package com.blankspeaker.grld

import android.app.Application

class GRLDApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Drop pre-OAuth cookie sessions so only OAuth tokens count as signed-in
        GrokAuth.purgeLegacyAuthArtifacts(this)
        if (GrokAuth.isSignedIn(this)) {
            UsageMonitorService.start(this)
        }
    }
}
