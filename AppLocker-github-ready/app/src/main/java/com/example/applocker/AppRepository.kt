package com.example.applocker

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

data class AppEntry(
    val label: String,
    val pkg: String,
    val isSystem: Boolean,
    val icon: ImageBitmap
)

object AppRepository {

    private fun launcherIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    /** All launchable apps (excluding this one), sorted by label. Call off the main thread. */
    fun loadApps(ctx: Context, iconPx: Int = 96): List<AppEntry> {
        val pm = ctx.packageManager
        return pm.queryIntentActivities(launcherIntent(), 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { ri ->
                val flags = ri.activityInfo.applicationInfo.flags
                val system = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                AppEntry(
                    label = ri.loadLabel(pm).toString(),
                    pkg = ri.activityInfo.packageName,
                    isSystem = system,
                    icon = ri.loadIcon(pm).toBitmap(iconPx, iconPx).asImageBitmap()
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /** Package names only (cheap, no icons). */
    fun launchablePackages(ctx: Context): List<String> =
        ctx.packageManager.queryIntentActivities(launcherIntent(), 0)
            .map { it.activityInfo.packageName }
            .filter { it != ctx.packageName }
            .distinct()
}
