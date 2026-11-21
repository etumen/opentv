/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.opentv.core.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * DownloadManager tells us when a transfer ends (done or failed); that is the moment to start the
 * next queued download — downloads run one at a time for single-connection providers. Works with
 * the app closed: the system delivers this broadcast to our package directly.
 */
class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { ServiceLocator.get(context).downloadRepository.pump() }
            } finally {
                pending.finish()
            }
        }
    }
}
