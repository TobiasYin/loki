package dev.thor.agent

import android.content.Context
import android.os.SystemClock

/** Reads one already observed package; never enumerates installed applications. */
internal fun appContextMetadata(context: Context) = AppContextMetadataCache(
    now = { SystemClock.elapsedRealtime() },
    load = { packageName ->
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(packageName, 0)
        val label = info.applicationInfo?.let { app -> runCatching { pm.getApplicationLabel(app).toString() }.getOrNull() }
        AppContextMetadata(label, info.versionName, info.longVersionCode.toString())
    },
)
