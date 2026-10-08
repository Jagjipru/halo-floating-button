package com.halo.floatingbutton

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/** Restarts the floating button after a reboot, if enabled and permitted. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        // "Hide until I restart my phone" is satisfied by the reboot itself.
        if (Prefs.hideMode(context) == "restart") Prefs.clearHide(context)
        if (!Prefs.boot(context)) return
        if (!Settings.canDrawOverlays(context)) return
        val svc = Intent(context, FloatingButtonService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc)
        } else {
            context.startService(svc)
        }
    }
}
