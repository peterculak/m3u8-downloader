package com.ta3.downloader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ta3.downloader.*

/**
 * Full-screen movie page: the backdrop fills the screen behind, title / meta / description sit over its
 * lower part, and the prehraj.to stream list is a scrollable panel that slides up over the image.
 */
@Composable
fun MovieDetailScreen(
    state: UiState,
    onClose: () -> Unit,
    onExtractUrl: (PrehrajMovie) -> Unit,
    onDownload: (PrehrajMovie, String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onSeason: (Int) -> Unit,
    onEpisode: (Int, Int) -> Unit,
    onBackToEpisodes: () -> Unit
) {
    val item = state.movieDetail ?: return
    // On a series page, back from an episode's stream list returns to the episode list first
    val goBack = { if (item.isTv && state.seriesEpisode != null) onBackToEpisodes() else onClose() }
    BackHandler(onBack = goBack)
    val showStreams = !item.isTv || state.seriesEpisode != null

    val context = LocalContext.current
    val bg = MaterialTheme.colorScheme.background
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val listState = rememberLazyListState()
    val details = state.movieDetails
    val overview = (details?.overview?.takeIf { it.isNotBlank() } ?: item.overview).ifBlank { "Bez popisu." }

    Box(Modifier.fillMaxSize().background(bg)) {
        // Backdrop, fixed behind the scrolling content; it fades away as the content scrolls up over it
        // so the text stays readable.
        val fadePx = with(androidx.compose.ui.platform.LocalDensity.current) { (screenHeight * 0.5f).toPx() }
        val imageAlpha by remember {
            derivedStateOf {
                if (listState.firstVisibleItemIndex > 0) 0f
                else (1f - listState.firstVisibleItemScrollOffset / fadePx).coerceIn(0f, 1f)
            }
        }
        Box(Modifier.fillMaxWidth().height(screenHeight * 0.75f).graphicsLayer { alpha = imageAlpha }) {
            if (item.heroUrl != null) {
                AsyncImage(
                    model = item.heroUrl, contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to bg)))
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 48.dp)
        ) {
            // Lets the image show through before the text starts
            item { Spacer(Modifier.height(screenHeight * 0.38f)) }

            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Text(item.title, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground)
                    if (item.originalTitle.isNotEmpty() && item.originalTitle != item.title) {
                        Text(item.originalTitle, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (item.rating > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("%.1f".format(item.rating), fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp,
                                    color = MaterialTheme.colorScheme.onBackground)
                            }
                        }
                        val meta = listOfNotNull(
                            item.year.takeIf { it.isNotEmpty() },
                            details?.runtimeMinutes?.takeIf { it > 0 }?.let { "${it / 60} h ${it % 60} min" }
                        ).joinToString(" • ")
                        if (meta.isNotEmpty()) Text(meta, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    details?.genres?.takeIf { it.isNotEmpty() }?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it.joinToString(" • "), fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    details?.tagline?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(10.dp))
                        Text("„$it“", fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(overview, fontSize = 14.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(20.dp))
                }
            }

            // Stream list panel, slides up over the image
            item {
                Box(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .background(bg)
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    val count = state.prehrajSearchResults.size
                    Box(
                        Modifier.align(Alignment.TopCenter).offset(y = (-8).dp).width(40.dp).height(4.dp)
                            .clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    )
                    val ep = state.seriesEpisode
                    Text(
                        when {
                            !showStreams -> "Série a epizódy"
                            state.prehrajSearching -> "Hľadám streamy na prehraj.to…"
                            ep != null -> "S%02dE%02d%s • streamy (%d)".format(ep.season, ep.episode, if (ep.name.isNotBlank()) " – ${ep.name}" else "", count)
                            count > 0 -> "Streamy na prehraj.to ($count)"
                            else -> "Streamy na prehraj.to"
                        },
                        fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            if (!showStreams) {
                // Seasons + episodes (series): picking an episode runs the prehraj.to search for it
                item {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().background(bg).padding(bottom = 8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.browseSeasons, key = { it.number }) { sn ->
                            FilterChip(
                                selected = state.browseSelectedSeason == sn.number,
                                onClick = { onSeason(sn.number) },
                                label = { Text("Séria ${sn.number}", maxLines = 1) }
                            )
                        }
                    }
                }
                if (state.browseEpisodes.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().background(bg).padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                items(state.browseEpisodes, key = { "ep${it.number}" }) { ep ->
                    Box(Modifier.fillMaxWidth().background(bg).padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable { onEpisode(state.browseSelectedSeason, ep.number) }
                                .padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("E%02d".format(ep.number), color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.width(44.dp))
                            Text(ep.name.ifEmpty { "Epizóda ${ep.number}" }, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onBackground)
                        }
                    }
                }
            } else when {
                state.prehrajSearching -> item {
                    Box(Modifier.fillMaxWidth().background(bg).padding(32.dp),
                        contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                state.prehrajSearchError != null -> item {
                    Text(state.prehrajSearchError, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().background(bg).padding(20.dp))
                }
                state.prehrajSearchResults.isEmpty() -> item {
                    Text("Žiadne streamy sa nenašli", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().background(bg).padding(20.dp))
                }
                else -> items(state.prehrajSearchResults, key = { it.pageUrl }) { movie ->
                    Box(Modifier.fillMaxWidth().background(bg).padding(horizontal = 12.dp, vertical = 5.dp)) {
                        PrehrajMovieCard(
                            movie = movie,
                            activeDownload = state.activeDownloads[movie.pageUrl],
                            isDownloaded = state.downloadedFiles.any { it.episodeUrl == movie.pageUrl },
                            resolvedUrl = state.prehrajResolvedUrls[movie.pageUrl],
                            onExtractUrl = { onExtractUrl(movie) },
                            onDownload = { url -> onDownload(movie, url) },
                            onPlay = { url -> playUrlInExternalPlayer(context, url, movie.title) },
                            onShare = { url ->
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_SUBJECT, movie.title)
                                    putExtra(android.content.Intent.EXTRA_TEXT, url)
                                }
                                context.startActivity(android.content.Intent.createChooser(intent, "Zdieľať URL videa"))
                            },
                            onCancel = { onCancelDownload(movie.pageUrl) }
                        )
                    }
                }
            }
        }

        // Keeps the status bar legible once the list scrolls underneath it
        val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        if (scrolled) {
            Box(
                Modifier.fillMaxWidth().height(96.dp)
                    .background(Brush.verticalGradient(0f to bg, 0.6f to bg.copy(alpha = 0.85f), 1f to Color.Transparent))
            )
        }

        // Back button
        IconButton(
            onClick = goBack,
            modifier = Modifier.statusBarsPadding().padding(8.dp).clip(CircleShape).background(Color(0x99000000))
        ) { Icon(Icons.Default.ArrowBack, "Späť", tint = Color.White) }
    }
}
