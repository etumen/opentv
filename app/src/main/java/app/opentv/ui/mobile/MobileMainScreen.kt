/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.mobile

import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import app.opentv.R
import app.opentv.core.findActivity
import app.opentv.core.StatusBus
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.opentv.core.AppSettings
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Recording
import app.opentv.data.model.Series
import app.opentv.data.provider.ProviderItem
import app.opentv.ui.LayoutClass
import app.opentv.ui.LocalLayoutClass
import app.opentv.ui.channels.HomeScreen
import app.opentv.ui.home.MobileHomeScreen
import app.opentv.ui.recordings.RecordingsScreen
import androidx.compose.material.icons.filled.Download
import app.opentv.ui.vod.AsianDramaScreen as DramaScreen
import app.opentv.ui.vod.MoviesScreen
import app.opentv.ui.vod.SeriesScreen

/**
 * The shell: a slim navigation rail down the left over a content area. The rail sits collapsed as
 * an icon strip and expands to show labels the moment focus lands in it — the TiviMate-style side
 * menu people asked for, instead of a top bar that ate a row of the guide. It overlays the content
 * rather than pushing it, so expanding the menu never reflows the guide underneath.
 */
enum class Tab(val labelRes: Int, val icon: ImageVector) {
    HOME(R.string.nav_home, Icons.Filled.Home),
    MOVIES(R.string.nav_movies, Icons.Filled.Movie),
    SHOWS(R.string.nav_shows, Icons.Filled.Tv),
    DRAMA(R.string.nav_drama, Icons.Filled.AutoAwesome),
    LIVE(R.string.mobile_nav_live_tv, Icons.Filled.LiveTv),
    RECORDINGS(R.string.nav_recordings, Icons.Filled.FiberManualRecord),
    DOWNLOADS(R.string.nav_downloads, Icons.Filled.Download),
}

private val RAIL_COLLAPSED = 76.dp
private val RAIL_EXPANDED = 236.dp

@Composable
fun MobileMainScreen(
    isTelevision: Boolean,
    hasSources: Boolean,
    isSyncing: Boolean,
    onPlayChannel: (Channel) -> Unit,
    onOpenMovie: (Movie) -> Unit,
    onOpenCloudMovie: (ProviderItem) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onOpenCloudSeries: (ProviderItem) -> Unit,
    onResume: (mediaKey: String, url: String, title: String) -> Unit,
    onAddSource: () -> Unit,
    onRefresh: () -> Unit,
    /** Opens search pre-filtered to a scope: "all", "movies" or "series". */
    onOpenSearch: (scope: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    onPlayRecording: (Recording) -> Unit,
    onPlayCatchup: (mediaKey: String, url: String, title: String, ua: String) -> Unit,
    activeProfileName: String,
    onOpenRecordings: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onPlayCloudItem: (ProviderItem, () -> Unit) -> Unit = { _, done -> done() },
) {
    // Content-type toggles: a switched-off type has its tab hidden here (and its sync skipped in
    // CatalogRepository). Recordings is never a content type, so it always stays — which also means
    // there is always at least one tab and never a blank shell, even with all three types off.
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val liveEnabled by settings.liveEnabled.collectAsState()
    val moviesEnabled by settings.moviesEnabled.collectAsState()
    val seriesEnabled by settings.seriesEnabled.collectAsState()
    val isPhone = LocalLayoutClass.current != LayoutClass.TV
    val visibleTabs = remember(isPhone, liveEnabled, moviesEnabled, seriesEnabled) {
        if (isPhone) {
            listOf(Tab.HOME, Tab.MOVIES, Tab.SHOWS, Tab.DRAMA, Tab.LIVE)
        } else {
            buildList {
                if (liveEnabled) add(Tab.LIVE)
                if (moviesEnabled) add(Tab.MOVIES)
                if (seriesEnabled) {
                    add(Tab.SHOWS)
                    add(Tab.DRAMA)
                }
                add(Tab.RECORDINGS)
                add(Tab.DOWNLOADS)
            }
        }
    }
    val homeTab = if (isPhone) {
        Tab.HOME
    } else {
        if (!hasSources && Tab.MOVIES in visibleTabs) Tab.MOVIES
        else visibleTabs.firstOrNull() ?: Tab.LIVE
    }

    // Saveable so it survives leaving for the player or a detail page: Back from an episode used
    // to land on Live TV because this state was rebuilt from scratch when the shell came back.
    var tab by rememberSaveable { mutableStateOf(homeTab) }

    // If the selected tab gets hidden (its type toggled off while it's open), drop back to the
    // home tab so the content area never tries to show a tab that's no longer there.
    LaunchedEffect(visibleTabs) {
        if (tab !in visibleTabs) tab = homeTab
    }

    // Back from a non-home tab returns to the home tab rather than dropping out of the app.
    BackHandler(enabled = tab != homeTab) { tab = homeTab }

    // On the home tab, Back would otherwise drop straight out to the TV launcher — one stray press
    // and you've closed the app. Ask first. (A dialog or panel open in a child screen swallows Back
    // before this, so this only fires at the true root.)
    var showExit by remember { mutableStateOf(false) }
    BackHandler(enabled = tab == homeTab) { showExit = true }
    if (showExit) {
        AlertDialog(
            onDismissRequest = { showExit = false },
            title = { Text(stringResource(R.string.exit_title)) },
            text = { Text(stringResource(R.string.exit_body)) },
            confirmButton = {
                TextButton(onClick = { showExit = false; context.findActivity()?.finish() }) {
                    Text(stringResource(R.string.exit_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExit = false }) { Text(stringResource(R.string.exit_cancel)) }
            },
        )
    }

    val content: @Composable () -> Unit = {
        when (tab) {
            Tab.HOME -> MobileHomeScreen(
                hasSources = hasSources,
                onResume = onResume,
                onOpenMovie = onOpenMovie,
                onOpenCloudMovie = onOpenCloudMovie,
                onOpenSeries = onOpenSeries,
                onOpenCloudSeries = onOpenCloudSeries,
                onPlayCloudItem = onPlayCloudItem,
                onSelectTab = { tab = it },
            )
            Tab.LIVE -> {
                if (isPhone && !liveEnabled) {
                    DisabledContentEmptyState(
                        title = stringResource(R.string.nav_live_tv),
                        description = stringResource(R.string.settings_content_note),
                    )
                } else {
                    HomeScreen(
                        isTelevision = isTelevision,
                        hasSources = hasSources,
                        isSyncing = isSyncing,
                        onPlayChannel = onPlayChannel,
                        onAddSource = onAddSource,
                        onRefresh = onRefresh,
                        onPlayCatchup = onPlayCatchup,
                    )
                }
            }
            Tab.MOVIES -> {
                if (isPhone && !moviesEnabled) {
                    DisabledContentEmptyState(
                        title = stringResource(R.string.nav_movies),
                        description = stringResource(R.string.settings_content_note),
                    )
                } else {
                    MoviesScreen(
                        onOpenMovie = onOpenMovie,
                        onOpenCloudMovie = onOpenCloudMovie,
                        onResume = onResume,
                        onOpenSearch = { onOpenSearch("movies") },
                        hasSources = hasSources,
                        isSyncing = isSyncing,
                    )
                }
            }
            Tab.SHOWS -> {
                if (isPhone && !seriesEnabled) {
                    DisabledContentEmptyState(
                        title = stringResource(R.string.nav_shows),
                        description = stringResource(R.string.settings_content_note),
                    )
                } else {
                    SeriesScreen(
                        onOpenSeries = onOpenSeries,
                        onOpenCloudSeries = onOpenCloudSeries,
                        onResume = onResume,
                        onOpenSearch = { onOpenSearch("series") },
                        hasSources = hasSources,
                        isSyncing = isSyncing,
                    )
                }
            }
            Tab.DRAMA -> {
                if (isPhone && !seriesEnabled) {
                    DisabledContentEmptyState(
                        title = stringResource(R.string.nav_drama),
                        description = stringResource(R.string.settings_content_note),
                    )
                } else {
                    DramaScreen(
                        onOpenCloudSeries = onOpenCloudSeries,
                        onOpenSearch = { onOpenSearch("series") },
                    )
                }
            }
            Tab.RECORDINGS -> RecordingsScreen(onPlay = onPlayRecording)
            // Saved files play through the VOD player under the same media key, so resume works.
            // Offline-download persistence will be migrated separately from the mobile database;
            // never silently read incompatible TV database schemas.
            Tab.DOWNLOADS -> DisabledContentEmptyState(
                title = stringResource(R.string.nav_downloads),
                description = "İndirilenler veri taşıma işlemi henüz tamamlanmadı.",
            )
        }
    }

    // Phones get a touch shell: a top app bar for the global actions and a bottom tab bar, so the
    // full portrait width goes to the content instead of a TV-sized side rail.
    if (LocalLayoutClass.current != LayoutClass.TV) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            PhoneTopBar(
                isSyncing = isSyncing,
                onRefresh = onRefresh,
                onOpenSearch = {
                    onOpenSearch(
                        when (tab) {
                            Tab.MOVIES -> "movies"
                            Tab.SHOWS, Tab.DRAMA -> "series"
                            else -> "all"
                        },
                    )
                },
                onOpenRecordings = { tab = Tab.RECORDINGS },
                onOpenDownloads = { tab = Tab.DOWNLOADS },
                onOpenSettings = onOpenSettings,
                onOpenProfiles = onOpenProfiles,
                activeProfileName = activeProfileName,
            )
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            StatusBar()
            androidx.compose.material3.HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                thickness = 0.5.dp,
            )
            NavigationBar(
                containerColor = Color(0xFF08080B),
                tonalElevation = 0.dp,
            ) {
                visibleTabs.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                t.icon,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                            )
                        },
                        colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFFE50914),
                            selectedTextColor = Color(0xFFE50914),
                            indicatorColor = Color(0xFFE50914).copy(alpha = 0.16f),
                            unselectedIconColor = Color(0xFF8E92A2),
                            unselectedTextColor = Color(0xFF8E92A2),
                        ),
                        label = {
                            Text(
                                stringResource(t.labelRes),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    letterSpacing = (-0.2).sp,
                                    lineHeight = 12.sp,
                                ),
                                fontWeight = if (tab == t) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        alwaysShowLabel = true,
                    )
                }
            }
        }
        return
    }

    // The rail sits beside the content and pushes it, rather than floating over it. The Live TV
    // screen has its own category rail down its left edge, and an overlaying menu would land on top
    // of it and leave a sliver poking out — so they live side by side and never collide.
    Column(Modifier.fillMaxSize()) {
      Row(Modifier.weight(1f).fillMaxWidth()) {
        NavRail(
            tabs = visibleTabs,
            current = tab,
            onSelect = { tab = it },
            onOpenSearch = { onOpenSearch("all") },
            onOpenSettings = onOpenSettings,
            onOpenProfiles = onOpenProfiles,
            activeProfileName = activeProfileName,
        )

        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
      }
      StatusBar()
    }
}

@Composable
private fun PhoneTopBar(
    isSyncing: Boolean,
    onRefresh: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    activeProfileName: String,
    onOpenRecordings: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_opentv_logo),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(4.dp))
        Wordmark(Modifier.weight(1f))
        // Re-sync every provider: new channels, films and series, plus the guide. Spins while any
        // sync (manual or scheduled) runs, so a second tap can't stack another one.
        IconButton(onClick = onRefresh, enabled = !isSyncing, modifier = Modifier.size(36.dp)) {
            if (isSyncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.phone_refresh))
        }
        IconButton(onClick = onOpenSearch, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.nav_search))
        }
        IconButton(onClick = onOpenRecordings, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.FiberManualRecord, contentDescription = stringResource(R.string.nav_recordings), tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = onOpenDownloads, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.nav_downloads))
        }
        IconButton(onClick = onOpenProfiles, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Person, contentDescription = activeProfileName)
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.nav_settings))
        }
    }
}

/**
 * A slim line along the bottom that says what the app is doing in the background — loading
 * channels, building the guide, loading movies — so a slow moment on a big provider reads as work
 * in progress, not a frozen screen. Invisible when there's nothing to report.
 */
@Composable
private fun StatusBar() {
    val message by StatusBus.message.collectAsState()
    val progress by StatusBus.progress.collectAsState()
    val text = message ?: return
    val p = progress
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (p == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    "${(p * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (p != null) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { p },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun NavRail(
    tabs: List<Tab>,
    current: Tab,
    onSelect: (Tab) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    activeProfileName: String,
    modifier: Modifier = Modifier,
) {
    // Expand whenever focus is anywhere inside the rail; collapse back to icons when it leaves.
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(
        targetValue = if (expanded) RAIL_EXPANDED else RAIL_COLLAPSED,
        label = "railWidth",
    )

    Column(
        modifier
            .width(width)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .focusGroup()
            .onFocusChanged { expanded = it.hasFocus }
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Brand: the logo mark alone when collapsed, the mark + "OpenTV" wordmark when open. The
        // name stays on purpose — it's what people search for.
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_opentv_logo),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(34.dp),
            )
            if (expanded) {
                Spacer(Modifier.width(12.dp))
                Wordmark(isPhone = false)
            }
        }
        Spacer(Modifier.height(8.dp))

        tabs.forEach { t ->
            RailItem(t.icon, stringResource(t.labelRes), expanded, current == t) { onSelect(t) }
        }

        Spacer(Modifier.height(1.dp).fillMaxWidth())
        Spacer(Modifier.weight(1f))

        RailItem(Icons.Filled.Search, stringResource(R.string.nav_search), expanded, false, onOpenSearch)
        RailItem(Icons.Filled.Person, activeProfileName, expanded, false, onOpenProfiles)
        RailItem(Icons.Filled.Settings, stringResource(R.string.nav_settings), expanded, false, onOpenSettings)
    }
}

@Composable
private fun RailItem(
    icon: ImageVector,
    label: String,
    expanded: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    val tint = if (focused) MaterialTheme.colorScheme.onPrimary
    else if (selected) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = tint)
        if (expanded) {
            Spacer(Modifier.width(14.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = tint,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The "OpenTV / MOBILE" wordmark: the upstream name, with what this app is set small underneath
 * — credit to the project it grew from, and plain about being the phone edition.
 */
@Composable
private fun Wordmark(modifier: Modifier = Modifier, isPhone: Boolean = true) {
    Column(modifier) {
        Text(
            "OpenTV",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
        )
        if (isPhone) {
            Text(
                "MOBILE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 3.sp,
                maxLines = 1,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

@Composable
private fun DisabledContentEmptyState(title: String, description: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
