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
import app.opentv.ui.vod.DiziPalSessionAnchor
import app.opentv.ui.vod.PosterCard
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

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    val hero = remember(shelves) {
        shelves.asSequence()
            .flatMap { it.items.asSequence() }
            .firstOrNull { !it.backdropUrl.isNullOrBlank() }
            ?: shelves.firstOrNull()?.items?.firstOrNull()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        DiziPalSessionAnchor()

        if (loading && shelves.isEmpty()) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            hero?.let { item ->
                item(key = "hero:" + item.id) {
                    DiscoveryHero(
                        content = item,
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
            .height(300.dp),
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
                        .padding(end = 36.dp, top = 18.dp, bottom = 18.dp)
                        .width(152.dp)
                        .height(228.dp)
                        .clip(RoundedCornerShape(18.dp)),
                )
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.background.copy(alpha = 0.88f),
                            Color.Transparent,
                        ),
                        startX = 0f,
                        endX = 1150f,
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
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 32.dp, end = 24.dp)
                .width(460.dp),
        ) {
            Text(
                text = content.title,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))

            HeroMetadata(content)
            Spacer(Modifier.height(12.dp))

            content.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(20.dp))
            }

            HeroAction(onClick = onClick)
        }
    }
}

@Composable
private fun HeroMetadata(content: CanonicalContent) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content.rating?.takeIf { it > 0.0 }?.let { rating ->
            Text(
                text = "★ " + String.format("%.1f", rating),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }

        if (content.externalIds.containsKey("imdb")) {
            Text(
                text = "IMDb",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF121212),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFF5C518))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        content.year?.let { year ->
            Text(
                text = year.toString(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        content.genres.take(3).forEach { genre ->
            Text(
                text = "•",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            )
            Text(
                text = genre,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun HeroAction(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        label = "heroActionScale",
    )
    val background = if (focused) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.White.copy(alpha = 0.96f)
    }
    val foreground = if (focused) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        Color.Black
    }

    Row(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = foreground,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "İzle",
            style = MaterialTheme.typography.titleMedium,
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
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
