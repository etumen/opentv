/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.opentv.core.findActivity
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Touch gestures over the video, the way phone players work:
 *  - vertical drag on the left half: screen brightness; on the right half: media volume;
 *  - horizontal swipe: [onSwipe] with -1 (swiped left) or +1 (swiped right) — the caller decides
 *    what that means (zap channels on live, seek on VOD).
 *
 * Taps on the video land here ([onTap]) rather than on a handler around the whole player: this
 * layer sits *under* the controls, so a tap on a button never also reaches it. (A root-level tap
 * handler saw button taps too — the channel-list button opened the list and the same tap closed
 * it again.) Brightness is a window override for this screen only, released when it closes.
 */
@Composable
fun BoxScope.PlayerGestureLayer(
    enabled: Boolean,
    onTap: () -> Unit,
    dragsEnabled: Boolean = true,
    onSwipe: (direction: Int) -> Unit,
) {
    if (!enabled) return
    val context = LocalContext.current
    val window = remember { context.findActivity()?.window }
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    val swipeThresholdPx = with(LocalDensity.current) { 80.dp.toPx() }

    // What the overlay shows while a drag adjusts something; cleared shortly after it ends.
    var indicator by remember { mutableStateOf<Pair<ImageVector, Float>?>(null) }
    var indicatorTick by remember { mutableStateOf(0) }
    LaunchedEffect(indicatorTick) {
        if (indicator != null) {
            delay(800)
            indicator = null
        }
    }

    DisposableEffect(window) {
        onDispose {
            window?.attributes = window?.attributes?.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    fun currentBrightness(): Float {
        val override = window?.attributes?.screenBrightness ?: -1f
        if (override >= 0f) return override
        val system = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        return (system / 255f).coerceIn(0f, 1f)
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onTap() } }
            .pointerInput(dragsEnabled) {
                if (!dragsEnabled) return@pointerInput
                // Axis is decided by the first movement past slop, then locked for the drag.
                var axis = 0 // 0 = undecided, 1 = horizontal, 2 = vertical
                var totalX = 0f
                var leftHalf = true
                var level = 0f
                detectDragGestures(
                    onDragStart = { start ->
                        axis = 0
                        totalX = 0f
                        leftHalf = start.x < size.width / 2
                        level = if (leftHalf) currentBrightness()
                        else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
                    },
                    onDragEnd = {
                        if (axis == 1 && abs(totalX) > swipeThresholdPx) onSwipe(if (totalX < 0) -1 else 1)
                        indicatorTick++
                    },
                    onDragCancel = { indicatorTick++ },
                ) { change, drag ->
                    change.consume()
                    if (axis == 0) axis = if (abs(drag.x) > abs(drag.y)) 1 else 2
                    if (axis == 1) {
                        totalX += drag.x
                        return@detectDragGestures
                    }
                    // A full-height drag sweeps the whole range; dragging up raises the level.
                    level = (level - drag.y / size.height).coerceIn(0f, 1f)
                    if (leftHalf) {
                        window?.attributes = window?.attributes?.apply { screenBrightness = level.coerceAtLeast(0.01f) }
                        indicator = Icons.Filled.BrightnessMedium to level
                    } else {
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * maxVolume).toInt(), 0)
                        indicator = Icons.Filled.VolumeUp to level
                    }
                }
            },
    )

    indicator?.let { (icon, value) ->
        Column(
            Modifier
                .align(Alignment.Center)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { value }, modifier = Modifier.width(140.dp))
            Spacer(Modifier.height(6.dp))
            Text("${(value * 100).toInt()}%", color = Color.White)
        }
    }
}

/**
 * True full screen on touch devices: the status and navigation bars are hidden while [hidden]
 * (i.e. while the player's controls are away) and come back with the controls. A swipe from the
 * edge still peeks them transiently. Bars are always restored when the player closes.
 */
@Composable
fun ImmersiveSystemBars(hidden: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    val window = remember { view.context.findActivity()?.window }
    val controller = remember(window) {
        window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
    }
    val bars = androidx.core.view.WindowInsetsCompat.Type.systemBars()
    // One request per change. (Restoring in a keyed onDispose sent "show" then "hide" back to
    // back on every toggle, and the system sometimes applied them out of order — leaving the bars
    // inverted: visible over the video, gone while the controls were up.)
    LaunchedEffect(controller, hidden) {
        controller?.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) controller?.hide(bars) else controller?.show(bars)
    }
    DisposableEffect(controller) {
        onDispose { controller?.show(bars) }
    }
}

/**
 * Insets for player chrome that do NOT change when [ImmersiveSystemBars] hides or shows the bars.
 * With the regular safeDrawing insets the controls jumped by the status-bar height at the moment
 * the bars toggled — right under the finger — and the tap on the back / channel-list button was
 * cancelled. Reserving the bars' space whether visible or not keeps every button still.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
val PlayerChromeInsets: androidx.compose.foundation.layout.WindowInsets
    @Composable get() = androidx.compose.foundation.layout.WindowInsets.systemBarsIgnoringVisibility
        .union(androidx.compose.foundation.layout.WindowInsets.displayCutout)
