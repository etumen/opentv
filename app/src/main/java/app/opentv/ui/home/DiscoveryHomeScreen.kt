package app.opentv.ui.home

import android.webkit.CookieManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.data.catalog.CanonicalContent
import app.opentv.data.provider.ProviderItem
import app.opentv.data.provider.ProviderMediaType
import app.opentv.data.provider.dizipal.DiziPalProvider
import app.opentv.ui.LayoutClass
import app.opentv.ui.LocalLayoutClass
import app.opentv.ui.vod.DiziPalSessionAnchor
import app.opentv.ui.vod.PosterCard
import app.opentv.ui.vod.UnifiedCatalogRules
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun DiscoveryHomeScreen(
    onOpenMovie: (ProviderItem) -> Unit,
    onOpenSeries: (ProviderItem) -> Unit,
    viewModel: DiscoveryHomeViewModel = viewModel(),
) {
    val shelves by viewModel.shelves.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val isTv = LocalLayoutClass.current == LayoutClass.TV

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    val heroItems = remember(shelves) {
        val newest = shelves.firstOrNull { it.id == "new" }?.items.orEmpty()
        (newest + shelves.flatMap { it.items })
            .distinctBy { it.id }
            .filter { !it.backdropUrl.isNullOrBlank() || !it.posterUrl.isNullOrBlank() }
            .take(HERO_ITEM_LIMIT)
    }
    var heroIndex by remember(heroItems) { mutableStateOf(0) }
    var heroActionFocused by remember { mutableStateOf(false) }
    LaunchedEffect(heroItems.size, heroActionFocused) {
        if (heroItems.size > 1 && !heroActionFocused) {
            while (true) {
                delay(HERO_ROTATION_MS)
                heroIndex = (heroIndex + 1) % heroItems.size
            }
        }
    }
    val hero = heroItems.getOrNull(heroIndex)

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (!isTv) {
            DiziPalSessionAnchor()
        }

        if (loading && shelves.isEmpty()) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            hero?.let { item ->
                item(key = "hero") {
                    DiscoveryHero(
                        content = item,
                        indicatorCount = heroItems.size,
                        indicatorIndex = heroIndex,
                        onActionFocusChanged = { heroActionFocused = it },
                        onClick = {
                            openCanonical(item, onOpenMovie, onOpenSeries)
                        },
                    )
                }
            }

            items(shelves, key = { it.id }) { shelf ->
                PlatformShelf(
                    shelf = shelf,
                    onOpenMovie = onOpenMovie,
                    onOpenSeries = onOpenSeries,
                )
            }
        }
    }
}

@Composable
private fun DiscoveryHero(
    content: CanonicalContent,
    indicatorCount: Int,
    indicatorIndex: Int,
    onActionFocusChanged: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val source = content.sources.firstOrNull()
    val backdropModel = rememberProviderImageModel(
        url = content.backdropUrl,
        providerId = source?.providerId,
    )
    val posterModel = rememberProviderImageModel(
        url = content.posterUrl,
        providerId = source?.providerId,
    )

    Box(
        Modifier
            .fillMaxWidth()
            .height(220.dp),
    ) {
        when {
            backdropModel != null -> {
                AsyncImage(
                    model = backdropModel,
                    contentDescription = content.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            posterModel != null -> {
                AsyncImage(
                    model = posterModel,
                    contentDescription = content.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 48.dp, top = 12.dp, bottom = 12.dp)
                        .width(128.dp)
                        .height(192.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFF08080B),
                            Color(0xFF08080B).copy(alpha = 0.92f),
                            Color(0xFF08080B).copy(alpha = 0.38f),
                            Color.Transparent,
                        ),
                        startX = 0f,
                        endX = 1320f,
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Color.Transparent,
                            Color(0xFF08080B).copy(alpha = 0.94f),
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 48.dp, end = 40.dp, bottom = 10.dp)
                .width(470.dp),
        ) {
            Text(
                text = content.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(7.dp))
            HeroMetadata(content)

            val sourceClaims = remember(content.genres) {
                content.genres
                    .map(UnifiedCatalogRules::sourceClaimLabel)
                    .filter { it.startsWith("Kaynak:") }
                    .distinct()
                    .take(2)
            }
            if (sourceClaims.isNotEmpty()) {
                Spacer(Modifier.height(7.dp))
                HeroSourceClaims(sourceClaims)
            }

            content.description?.takeIf { it.isNotBlank() }?.let { description ->
                Spacer(Modifier.height(7.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(10.dp))
            HeroAction(
                onClick = onClick,
                onFocusChanged = onActionFocusChanged,
            )
        }

        if (indicatorCount > 1) {
            HeroIndicators(
                count = indicatorCount,
                selected = indicatorIndex,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun HeroMetadata(content: CanonicalContent) {
    val displayGenres = remember(content.genres) {
        content.genres
            .map(UnifiedCatalogRules::sourceClaimLabel)
            .filterNot { it.startsWith("Kaynak:") }
            .take(1)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        content.rating?.takeIf { it > 0.0 }?.let { rating ->
            val hasImdb = content.externalIds.containsKey("imdb")
            Text(
                text = if (hasImdb) {
                    "IMDb %.1f".format(java.util.Locale.US, rating)
                } else {
                    "★ %.1f".format(java.util.Locale.US, rating)
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (hasImdb) Color(0xFF121212) else Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (hasImdb) Color(0xFFF5C518) else Color.Black.copy(alpha = 0.56f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        content.year?.let { year ->
            Text(
                text = year.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.82f),
            )
        }

        displayGenres.forEach { genre ->
            Text(
                text = "•",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.56f),
            )
            Text(
                text = genre,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.82f),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun HeroSourceClaims(values: List<String>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        values.forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.82f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun HeroIndicators(
    count: Int,
    selected: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            Box(
                Modifier
                    .width(if (index == selected) 28.dp else 8.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (index == selected) MaterialTheme.colorScheme.primary
                        else Color.White.copy(alpha = 0.38f),
                    ),
            )
        }
    }
}

@Composable
private fun HeroAction(
    onClick: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        label = "heroActionScale",
    )
    val background = if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.96f)
    val foreground = if (focused) MaterialTheme.colorScheme.onPrimary else Color.Black

    Row(
        modifier = Modifier
            .width(104.dp)
            .height(40.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged {
                focused = it.isFocused
                onFocusChanged(it.isFocused)
            }
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = foreground,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "İzle",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = foreground,
        )
    }
}

@Composable
private fun PlatformShelf(
    shelf: DiscoveryShelf,
    onOpenMovie: (ProviderItem) -> Unit,
    onOpenSeries: (ProviderItem) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = shelf.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 3.dp),
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shelf.items, key = { shelf.id + ":" + it.id }) { content ->
                val source = content.sources.firstOrNull()
                PosterCard(
                    title = content.title,
                    posterUrl = content.posterUrl,
                    providerId = source?.providerId,
                    subtitle = content.year?.toString(),
                    rating = content.rating,
                    onClick = {
                        openCanonical(content, onOpenMovie, onOpenSeries)
                    },
                )
            }
        }
    }
}

private fun openCanonical(
    content: CanonicalContent,
    onOpenMovie: (ProviderItem) -> Unit,
    onOpenSeries: (ProviderItem) -> Unit,
) {
    val source = content.sources.firstOrNull() ?: return
    val item = ProviderItem(
        providerId = source.providerId,
        id = source.itemId,
        title = content.title,
        mediaType = content.mediaType,
        posterUrl = content.posterUrl,
        backdropUrl = content.backdropUrl,
        description = content.description,
        year = content.year,
        rating = content.rating,
        genres = content.genres,
        externalIds = content.externalIds,
    )
    when (content.mediaType) {
        ProviderMediaType.MOVIE -> onOpenMovie(item)
        ProviderMediaType.SERIES -> onOpenSeries(item)
        ProviderMediaType.OTHER -> Unit
    }
}

private const val HERO_ITEM_LIMIT = 5
private const val HERO_ROTATION_MS = 8_000L

@Composable
private fun rememberProviderImageModel(
    url: String?,
    providerId: String?,
): Any? {
    val context = LocalContext.current
    return remember(url, providerId, context) {
        url?.let { imageUrl ->
            val diziPalAsset =
                providerId == DiziPalProvider.PROVIDER_ID ||
                    imageUrl.contains("cdnhipter.xyz", ignoreCase = true) ||
                    imageUrl.contains("dizipal", ignoreCase = true)

            if (!diziPalAsset) {
                imageUrl
            } else {
                ImageRequest.Builder(context)
                    .data(imageUrl)
                    .addHeader("User-Agent", DiziPalProvider.SITE_USER_AGENT)
                    .addHeader("Referer", DiziPalProvider.FALLBACK_BASE_URL + "/")
                    .addHeader("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                    .apply {
                        runCatching {
                            CookieManager.getInstance().getCookie(imageUrl)
                        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { cookie ->
                            addHeader("Cookie", cookie)
                        }
                    }
                    .build()
            }
        }
    }
}
