package com.example.applocker

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager

/**
 * DevicePolicyManager helpers. Everything here only works when this app is the
 * DEVICE OWNER (provisioned via `adb shell dpm set-device-owner` or QR/zero-touch enrollment).
 *
 * Deliberately does NOT take over the Home screen or pin tasks: the phone's normal launcher
 * (folders, wallpaper, icon layout) never changes. Two ways to block an app:
 *  - GREY_OUT (setPackagesSuspended): icon stays exactly where it is, greyed out, tapping fails.
 *  - HIDE (setApplicationHidden): icon disappears from the launcher entirely, as if uninstalled.
 *    Data is kept, and un-hiding brings it straight back.
 */
object PolicyManager {

    enum class BlockMode { GREY_OUT, HIDE }

    private fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private fun admin(ctx: Context): ComponentName = AppDeviceAdminReceiver.component(ctx)

    fun isAdminActive(ctx: Context) = dpm(ctx).isAdminActive(admin(ctx))
    fun isDeviceOwner(ctx: Context) = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    fun apply(
        ctx: Context, store: SecureStore, allowed: Set<String>,
        allPackages: Collection<String>, mode: BlockMode
    ): Boolean {
        if (!isDeviceOwner(ctx)) return false
        val dpm = dpm(ctx)
        val admin = admin(ctx)

        // Undo whatever was applied before, regardless of which mode it used, so switching
        // between grey-out and hide never leaves a stray app stuck in the old state.
        undoPrevious(ctx, store)

        val toBlock = allPackages.filter { it !in allowed && it != ctx.packageName }
        when (mode) {
            BlockMode.GREY_OUT -> {
                val failed = dpm.setPackagesSuspended(admin, toBlock.toTypedArray(), true).toSet()
                store.suspendedPackages = toBlock.filter { it !in failed }.toSet()
                store.hiddenPackages = emptySet()
            }
            BlockMode.HIDE -> {
                val hidden = toBlock.filter { dpm.setApplicationHidden(admin, it, true) }
                store.hiddenPackages = hidden.toSet()
                store.suspendedPackages = emptySet()
            }
        }
        store.blockMode = mode.name

        // Close the real bypass routes an employee could otherwise use:
        //  - clearing this app's data (would reset the PIN) or uninstalling any app
        //  - booting into safe mode (loads without this app's blocking policy)
        //  - factory reset (wipes device-owner status entirely)
        //  - enabling USB debugging and running `adb shell dpm remove-active-admin`
        //  - adding a second, unmanaged user profile
        RESTRICTIONS.forEach { dpm.addUserRestriction(admin, it) }
        return true
    }

    fun clear(ctx: Context, store: SecureStore) {
        if (!isDeviceOwner(ctx)) return
        undoPrevious(ctx, store)
        RESTRICTIONS.forEach { dpm(ctx).clearUserRestriction(admin(ctx), it) }
    }

    private fun undoPrevious(ctx: Context, store: SecureStore) {
        val dpm = dpm(ctx)
        val admin = admin(ctx)
        val suspended = store.suspendedPackages
        if (suspended.isNotEmpty()) dpm.setPackagesSuspended(admin, suspended.toTypedArray(), false)
        store.suspendedPackages = emptySet()
        store.hiddenPackages.forEach { dpm.setApplicationHidden(admin, it, false) }
        store.hiddenPackages = emptySet()
    }

    /** Gives up device-owner status (useful while testing so the app can be uninstalled). */
    @Suppress("DEPRECATION")
    fun releaseDeviceOwner(ctx: Context) {
        if (isDeviceOwner(ctx)) dpm(ctx).clearDeviceOwnerApp(ctx.packageName)
    }

    private val RESTRICTIONS = listOf(
        UserManager.DISALLOW_APPS_CONTROL,
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_DEBUGGING_FEATURES,
        UserManager.DISALLOW_ADD_USER
    )
}
