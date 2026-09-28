package com.example.applocker

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.os.Build
import android.os.UserManager

/**
 * DevicePolicyManager helpers. Everything in apply()/clear() only works when this app is the
 * DEVICE OWNER (provisioned via `adb shell dpm set-device-owner` or QR/zero-touch enrollment).
 * A plain "device admin" cannot call these APIs.
 */
object PolicyManager {

    private fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private fun admin(ctx: Context): ComponentName = AppDeviceAdminReceiver.component(ctx)

    fun isAdminActive(ctx: Context) = dpm(ctx).isAdminActive(admin(ctx))
    fun isDeviceOwner(ctx: Context) = dpm(ctx).isDeviceOwnerApp(ctx.packageName)
    fun isLockTaskPermitted(ctx: Context) = dpm(ctx).isLockTaskPermitted(ctx.packageName)

    fun apply(ctx: Context, store: SecureStore, allowed: Set<String>, allPackages: Collection<String>): Boolean {
        if (!isDeviceOwner(ctx)) return false
        val dpm = dpm(ctx)
        val admin = admin(ctx)

        // 1. Lock-task (kiosk) whitelist: these packages may run inside the locked task.
        dpm.setLockTaskPackages(admin, (allowed + ctx.packageName).toTypedArray())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_HOME)
        }

        // 2. Suspend everything that is not allowed (greyed out, cannot be launched).
        val previous = store.suspendedPackages
        if (previous.isNotEmpty()) dpm.setPackagesSuspended(admin, previous.toTypedArray(), false)
        val toSuspend = allPackages.filter { it !in allowed && it != ctx.packageName }
        val failed = dpm.setPackagesSuspended(admin, toSuspend.toTypedArray(), true).toSet()
        store.suspendedPackages = toSuspend.filter { it !in failed }.toSet()

        // 3. Make this app the default Home without asking the user.
        val home = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.addPersistentPreferredActivity(admin, home, ComponentName(ctx, MainActivity::class.java))

        // 4. Close common bypasses (clearing app data resets the PIN; safe mode disables the launcher).
        dpm.addUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL)
        dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
        return true
    }

    fun clear(ctx: Context, store: SecureStore) {
        if (!isDeviceOwner(ctx)) return
        val dpm = dpm(ctx)
        val admin = admin(ctx)
        val suspended = store.suspendedPackages
        if (suspended.isNotEmpty()) dpm.setPackagesSuspended(admin, suspended.toTypedArray(), false)
        store.suspendedPackages = emptySet()
        dpm.setLockTaskPackages(admin, emptyArray())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) dpm.setLockTaskFeatures(admin, 0)
        dpm.clearPackagePersistentPreferredActivities(admin, ctx.packageName)
        dpm.clearUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL)
        dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
    }

    /** Gives up device-owner status (useful while testing so the app can be uninstalled). */
    @Suppress("DEPRECATION")
    fun releaseDeviceOwner(ctx: Context) {
        if (isDeviceOwner(ctx)) dpm(ctx).clearDeviceOwnerApp(ctx.packageName)
    }
}
