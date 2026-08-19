package com.floatwm.launcher.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LaunchableApp(
    val packageName: String,
    val label: CharSequence,
    val icon: Drawable
)

object AppRepository {

    /**
     * Every activity that resolves ACTION_MAIN/CATEGORY_LAUNCHER -- i.e.
     * exactly the set of apps that would show up in a normal launcher's app
     * drawer. Requires the <queries> declaration in the manifest (API 30+
     * package visibility), not QUERY_ALL_PACKAGES.
     */
    suspend fun loadLaunchableApps(context: Context, excludePackage: String): List<LaunchableApp> =
        withContext(Dispatchers.Default) {
            val pm = context.packageManager
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(query, PackageManager.MATCH_ALL)

            resolved.asSequence()
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != excludePackage }
                .map { appInfo: ApplicationInfo ->
                    LaunchableApp(
                        packageName = appInfo.packageName,
                        label = appInfo.loadLabel(pm),
                        icon = appInfo.loadIcon(pm)
                    )
                }
                .sortedBy { it.label.toString().lowercase() }
                .toList()
        }
}
