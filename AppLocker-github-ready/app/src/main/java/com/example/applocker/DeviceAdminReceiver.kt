package com.example.applocker

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/** Device admin / device owner entry point. */
class AppDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled")
        Toast.makeText(context, "Device admin enabled", Toast.LENGTH_SHORT).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Disabling device admin removes the restrictions applied by App Locker."

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device admin disabled")
        // Admin-backed policies are gone, so forget which packages we had suspended.
        SecureStore(context).suspendedPackages = emptySet()
        Toast.makeText(context, "Device admin disabled", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "AppDeviceAdmin"
        fun component(context: Context) =
            ComponentName(context.applicationContext, AppDeviceAdminReceiver::class.java)
    }
}

/** Re-asserts device-owner policy after a reboot (policies normally persist; this is a safety net). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = SecureStore(context)
        if (store.restricted && PolicyManager.isDeviceOwner(context)) {
            try {
                PolicyManager.apply(context, store, store.allowedPackages, AppRepository.launchablePackages(context))
            } catch (e: Exception) {
                Log.w("BootReceiver", "Could not re-apply policy", e)
            }
        }
    }
}
