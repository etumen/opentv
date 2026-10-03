/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.opentv.core.ServiceLocator
import app.opentv.data.provider.dizipal.DiziPalProvider

@Composable
internal fun DiziPalChallengeDialog(
    onVerified: () -> Unit,
) {
    val context = LocalContext.current
    val session = remember(context) {
        (
            ServiceLocator.get(context)
                .providerRegistry
                .find(DiziPalProvider.PROVIDER_ID) as? DiziPalProvider
            )?.browserSession()
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Text(
                text = "DiziPal doğrulanıyor",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp),
            )
            Text(
                text = "Cloudflare kontrolü tamamlanınca bu ekran otomatik kapanır. " +
                    "Bir doğrulama kutusu görünürse bir kez tamamlaman yeterli.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            if (session == null) {
                Text(
                    text = "DiziPal tarayıcı oturumu hazırlanamadı.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(20.dp),
                )
            } else {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = {
                        session.verificationView(
                            targetOrigin = DiziPalProvider.FALLBACK_BASE_URL,
                            onReady = onVerified,
                        )
                    },
                )
            }
        }
    }

    DisposableEffect(session) {
        onDispose {
            session?.detachVerificationCallback()
        }
    }
}


@Composable
internal fun DiziPalSessionAnchor() {
    val context = LocalContext.current
    val session = remember(context) {
        (
            ServiceLocator.get(context)
                .providerRegistry
                .find(DiziPalProvider.PROVIDER_ID) as? DiziPalProvider
            )?.browserSession()
    } ?: return

    AndroidView(
        modifier = Modifier
            .size(1.dp)
            .alpha(0f),
        factory = {
            session.verificationView(
                targetOrigin = DiziPalProvider.FALLBACK_BASE_URL,
                onReady = {},
            ).apply {
                // This WebView only keeps the verified Cloudflare session alive. On Android TV a
                // hidden WebView is still focusable by default and can swallow D-pad/Enter events
                // that should go to the visible episode rows.
                isFocusable = false
                isFocusableInTouchMode = false
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
        },
    )

    DisposableEffect(session) {
        onDispose {
            session.detachVerificationCallback()
        }
    }
}
