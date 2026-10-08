package com.ta3.downloader.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ta3.downloader.*

class BrowseActions(
    val onLoad: () -> Unit,
    val onType: (String) -> Unit,
    val onGenre: (TmdbGenre?) -> Unit,
    val onLoadMoreGenre: () -> Unit,
    val onOpenItem: (TmdbItem) -> Unit,
    val onCloseDetail: () -> Unit,
    val onSeason: (Int) -> Unit,
    val onEpisode: (Int, Int) -> Unit,
    val scroll: BrowseScroll
)

private val rowTitles = listOf(
    TmdbApi.Category.TRENDING to "Trendy tento týždeň",
    TmdbApi.Category.POPULAR to "Populárne",
    TmdbApi.Category.TOP_RATED to "Najlepšie hodnotené"
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PrehrajBrowse(state: UiState, actions: BrowseActions, modifier: Modifier = Modifier) {
    LaunchedEffect(state.browseType) { actions.onLoad() }

    if (state.browseDetail != null) {
        SeriesDetail(state, actions, modifier)
        return
    }

    val type = state.browseType
    Column(modifier = modifier.fillMaxSize()) {
        // Movies / Series switch + genres on ONE row (saves vertical space); font scale capped
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = minOf(density.fontScale, 1.0f))) {
            val genres = state.browseGenres[type].orEmpty()
            LazyRow(
                state = actions.scroll.list("chips"),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { FilterChip(selected = type == "movie", onClick = { actions.onType("movie") }, label = { Text("Filmy", maxLines = 1) }) }
                item { FilterChip(selected = type == "tv", onClick = { actions.onType("tv") }, label = { Text("Seriály", maxLines = 1) }) }
                if (genres.isNotEmpty()) {
                    item { Spacer(Modifier.width(8.dp)) }
                    item {
                        FilterChip(selected = state.browseGenre == null, onClick = { actions.onGenre(null) }, label = { Text("Všetko", maxLines = 1) })
                    }
                    items(TmdbGenre.LISTS, key = { it.id }) { l ->
                        FilterChip(selected = state.browseGenre == l, onClick = { actions.onGenre(l) }, label = { Text(l.name, maxLines = 1) })
                    }
                    items(genres, key = { it.id }) { g ->
                        FilterChip(selected = state.browseGenre?.id == g.id, onClick = { actions.onGenre(g) }, label = { Text(g.name, maxLines = 1) })
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))

        state.browseError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }

        if (state.browseGenre != null) {
            // Genre grid with infinite scroll
            val gridState = actions.scroll.grid("grid:$type:${state.browseGenre.id}")
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(110.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(state.browseGenreItems, key = { _, i -> i.id }) { index, item ->
                    if (index >= state.browseGenreItems.size - 6) {
                        LaunchedEffect(state.browseGenreItems.size) { actions.onLoadMoreGenre() }
                    }
                    PosterCard(item, Modifier.fillMaxWidth()) { actions.onOpenItem(item) }
                }
            }
        } else if (state.browseLoading && state.browseRows.keys.none { it.startsWith("$type:") }) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            LazyColumn(
                state = actions.scroll.list("rows:$type"),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(rowTitles, key = { it.first.name }) { (cat, title) ->
                    val rowItems = state.browseRows["$type:${cat.path}"].orEmpty()
                    if (rowItems.isNotEmpty()) {
                        Column {
                            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                            LazyRow(
                                state = actions.scroll.list("row:$type:${cat.path}"),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(rowItems, key = { it.id }) { item ->
                                    PosterCard(item, Modifier.width(120.dp)) { actions.onOpenItem(item) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterCard(item: TmdbItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier = modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (item.posterUrl != null) {
                AsyncImage(
                    model = item.posterUrl, contentDescription = item.title,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Outlined.Movie, null, modifier = Modifier.align(Alignment.Center).size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            }
            if (item.rating > 0) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(4.dp)
                        .clip(RoundedCornerShape(6.dp)).background(Color_scrim)
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Star, null, tint = androidx.compose.ui.graphics.Color(0xFFFFC107), modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("%.1f".format(item.rating), color = androidx.compose.ui.graphics.Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(item.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground)
        if (item.year.isNotEmpty()) {
            Text(item.year, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val Color_scrim = androidx.compose.ui.graphics.Color(0xAA000000)

@Composable
private fun SeriesDetail(state: UiState, actions: BrowseActions, modifier: Modifier = Modifier) {
    val item = state.browseDetail ?: return
    LazyColumn(
        state = actions.scroll.list("series:${item.id}:${state.browseSelectedSeason}"),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.onCloseDetail) { Icon(Icons.Default.ArrowBack, "Späť") }
                Text(item.title, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PosterCard(item, Modifier.width(110.dp)) {}
                Text(item.overview.ifEmpty { "Bez popisu." }, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 9, overflow = TextOverflow.Ellipsis)
            }
        }
        if (state.browseSeasons.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.browseSeasons, key = { it.number }) { s ->
                        FilterChip(
                            selected = state.browseSelectedSeason == s.number,
                            onClick = { actions.onSeason(s.number) },
                            label = { Text("Séria ${s.number}") }
                        )
                    }
                }
            }
        }
        if (state.browseEpisodes.isEmpty() && state.browseSeasons.isNotEmpty()) {
            item { CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp)) }
        }
        items(state.browseEpisodes, key = { it.number }) { ep ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { actions.onEpisode(state.browseSelectedSeason, ep.number) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("E%02d".format(ep.number), color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.width(40.dp))
                Text(ep.name.ifEmpty { "Epizóda ${ep.number}" }, fontSize = 14.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}
