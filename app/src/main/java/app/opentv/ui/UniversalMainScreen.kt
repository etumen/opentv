package app.opentv.ui

import androidx.compose.runtime.Composable
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.Recording
import app.opentv.data.provider.ProviderItem
import app.opentv.ui.mobile.MobileMainScreen

/**
 * The two independent UI shells share the same process, player and provider registry.
 * Android TV retains its existing d-pad-oriented layout; touch devices use mobile UI.
 */
@Composable
fun UniversalMainScreen(
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
    onOpenSearch: (scope: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    onPlayRecording: (Recording) -> Unit,
    onPlayCatchup: (mediaKey: String, url: String, title: String, ua: String) -> Unit,
    onPlayCloudItem: (ProviderItem, () -> Unit) -> Unit,
    activeProfileName: String,
) {
    val isTv = isTelevision || LocalLayoutClass.current == LayoutClass.TV
    if (isTv) {
        MainScreen(
            isTelevision = true,
            hasSources = hasSources,
            isSyncing = isSyncing,
            onPlayChannel = onPlayChannel,
            onOpenMovie = onOpenMovie,
            onOpenCloudMovie = onOpenCloudMovie,
            onOpenSeries = onOpenSeries,
            onOpenCloudSeries = onOpenCloudSeries,
            onResume = onResume,
            onAddSource = onAddSource,
            onRefresh = onRefresh,
            onOpenSearch = { onOpenSearch("all") },
            onOpenSettings = onOpenSettings,
            onOpenProfiles = onOpenProfiles,
            onPlayRecording = onPlayRecording,
            onPlayCatchup = onPlayCatchup,
            activeProfileName = activeProfileName,
        )
    } else {
        MobileMainScreen(
            isTelevision = false,
            hasSources = hasSources,
            isSyncing = isSyncing,
            onPlayChannel = onPlayChannel,
            onOpenMovie = onOpenMovie,
            onOpenCloudMovie = onOpenCloudMovie,
            onOpenSeries = onOpenSeries,
            onOpenCloudSeries = onOpenCloudSeries,
            onResume = onResume,
            onAddSource = onAddSource,
            onRefresh = onRefresh,
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
            onOpenProfiles = onOpenProfiles,
            onPlayRecording = onPlayRecording,
            onPlayCatchup = onPlayCatchup,
            onPlayCloudItem = onPlayCloudItem,
            activeProfileName = activeProfileName,
        )
    }
}
