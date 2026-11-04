/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Android 13+ only shows an app's notifications after the user grants POST_NOTIFICATIONS at
 * runtime. Without asking, reminders, recording alerts and "download finished" silently never
 * appeared on most current phones. This asks at the first moment a notification is actually
 * wanted (a reminder, a recording, a download) — once; Android itself won't re-ask after a "no".
 */
object NotificationPermission {
    private var askedThisSession = false

    fun granted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Asks if needed; a no-op below Android 13, when granted, or when already asked this run. */
    fun askIfNeeded(context: Context) {
        if (granted(context) || askedThisSession) return
        val activity = context.findActivity() as? Activity ?: return
        askedThisSession = true
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE)
    }

    private const val REQUEST_CODE = 4201
}
