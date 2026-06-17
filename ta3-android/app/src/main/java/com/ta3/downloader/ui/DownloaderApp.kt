package com.ta3.downloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ta3.downloader.*

@Composable
fun DownloaderApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "TA3 Downloader",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                )
                Text(
                    text = "Slovenský TV archív",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                Spacer(Modifier.height(12.dp))

                // 5-tab bar: TA3 | STVR | Stiahnuté | Prehraj | Nastavenia
                // Horizontally scrollable so labels never get cut off
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    TabItem(
                        icon = Icons.Outlined.PlayCircle,
                        label = "TA3",
                        selected = state.selectedTab == Tab.EPISODES,
                        onClick = { viewModel.selectTab(Tab.EPISODES) }
                    )
                    TabItem(
                        icon = Icons.Outlined.Tv,
                        label = "STVR",
                        selected = state.selectedTab == Tab.STVR,
                        onClick = { viewModel.selectTab(Tab.STVR) }
                    )
                    TabItem(
                        icon = Icons.Outlined.FolderOpen,
                        label = "Stiahnuté (${state.downloadedFiles.size})",
                        selected = state.selectedTab == Tab.DOWNLOADS,
                        onClick = { viewModel.selectTab(Tab.DOWNLOADS) }
                    )
                    TabItem(
                        icon = Icons.Outlined.Movie,
                        label = "Prehraj",
                        selected = state.selectedTab == Tab.PREHRAJ,
                        onClick = { viewModel.selectTab(Tab.PREHRAJ) }
                    )
                    TabItem(
                        icon = Icons.Outlined.Settings,
                        label = "Nastavenia",
                        selected = state.selectedTab == Tab.SETTINGS,
                        onClick = { viewModel.selectTab(Tab.SETTINGS) }
                    )
                }
            }
        }
    ) { innerPadding ->
        when (state.selectedTab) {
            Tab.EPISODES -> EpisodesTab(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.padding(innerPadding)
            )
            Tab.STVR -> StvrTab(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.padding(innerPadding)
            )
            Tab.DOWNLOADS -> DownloadsTab(
                state = state,
                onDelete = { viewModel.deleteDownload(it.episodeUrl) },
                onOpen = { openFileWithPlayer(context, it) },
                onClearAll = { viewModel.clearAllDownloads() },
                modifier = Modifier.padding(innerPadding)
            )
            Tab.PREHRAJ -> PrehrajTab(
                state = state,
                onSearchQueryChange = { viewModel.setPrehrajSearchQuery(it) },
                onSearch = { viewModel.searchPrehraj() },
                onExtractUrl = { viewModel.extractPrehrajUrl(it) },
                onDownload = { movie, url -> viewModel.downloadPrehrajMovie(movie, url) },
                onCancelDownload = { viewModel.cancelPrehrajDownload(it) },
                modifier = Modifier.padding(innerPadding)
            )
            Tab.SETTINGS -> SettingsTab(
                state = state,
                onIntervalChange = { viewModel.setSyncInterval(it) },
                onAutoDownloadToggle = { viewModel.setAutoDownloadEnabled(it) },
                onWifiOnlyToggle = { viewModel.setWifiOnly(it) },
                onShowToggle = { name, enabled -> viewModel.setShowEnabled(name, enabled) },
                onStvrShowToggle = { name, enabled -> viewModel.setStvrShowEnabled(name, enabled) },
                onPrehrajEmailChange = { viewModel.setPrehrajEmail(it) },
                onPrehrajPasswordChange = { viewModel.setPrehrajPassword(it) },
                onPrehrajLogin = { viewModel.loginPrehraj() },
                modifier = Modifier.padding(innerPadding)
            )
        }

        // Active downloads floating banner
        if (state.activeDownloads.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                ActiveDownloadsBanner(
                    downloads = state.activeDownloads.values.toList(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = label,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ─── Episodes Tab ──────────────────────────────────────────────────────────────

@Composable
fun EpisodesTab(state: UiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TA3_SHOWS.forEach { show ->
                val selected = show.name == state.selectedShow.name
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.selectShow(show) },
                    label = { Text(show.displayName, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        // Search bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onBackground),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (state.searchQuery.isEmpty()) {
                            Text("Hľadať epizódy...", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                        inner()
                    }
                )
                if (state.searchQuery.isNotEmpty()) {
                    Icon(Icons.Default.Close, "Vymazať",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp).clickable { viewModel.setSearchQuery("") })
                }
            }
        }

        when {
            state.loadingEpisodes -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text("Načítavam epizódy...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            state.episodeLoadError != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ErrorOutline, null,
                            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(state.episodeLoadError, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { viewModel.fetchEpisodes() }) { Text("Opakovať") }
                    }
                }
            }
            else -> {
                val episodes = viewModel.filteredEpisodes
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (episodes.isEmpty()) {
                        item {
                            Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Žiadne epizódy", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        items(episodes, key = { it.url }) { episode ->
                            EpisodeCard(
                                episode = episode,
                                isDownloaded = state.downloadedFiles.any { it.episodeUrl == episode.url },
                                activeDownload = state.activeDownloads[episode.url],
                                onDownload = { viewModel.startDownload(episode) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StvrTab(state: UiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            STVR_SHOWS.forEach { show ->
                val selected = show.name == state.selectedStvrShow.name
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.selectStvrShow(show) },
                    label = { Text(show.displayName, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        // Search bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = state.stvrSearchQuery,
                    onValueChange = viewModel::setStvrSearchQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onBackground),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (state.stvrSearchQuery.isEmpty()) {
                            Text("Hľadať epizódy...", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                        inner()
                    }
                )
                if (state.stvrSearchQuery.isNotEmpty()) {
                    Icon(Icons.Default.Close, "Vymazať",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp).clickable { viewModel.setStvrSearchQuery("") })
                }
            }
        }

        when {
            state.stvrLoadingEpisodes -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text("Načítavam epizódy...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            state.stvrEpisodeLoadError != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ErrorOutline, null,
                            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(state.stvrEpisodeLoadError, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { viewModel.fetchStvrEpisodes() }) { Text("Opakovať") }
                    }
                }
            }
            else -> {
                val episodes = viewModel.filteredStvrEpisodes
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (episodes.isEmpty()) {
                        item {
                            Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Žiadne epizódy", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        items(episodes, key = { it.url }) { episode ->
                            EpisodeCard(
                                episode = episode,
                                isDownloaded = state.downloadedFiles.any { it.episodeUrl == episode.url },
                                activeDownload = state.activeDownloads[episode.url],
                                onDownload = { viewModel.startStvrDownload(episode) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EpisodeCard(
    episode: Episode,
    isDownloaded: Boolean,
    activeDownload: ActiveDownload?,
    onDownload: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (episode.date.isNotEmpty()) {
                Text(episode.date, color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                Spacer(Modifier.height(4.dp))
            }
            Text(episode.title, color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Spacer(Modifier.height(10.dp))

            when {
                activeDownload != null -> {
                    Column {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Text(
                                when (activeDownload.status) {
                                    DownloadStatus.RESOLVING -> "Spracúvam stream..."
                                    DownloadStatus.DONE -> "Hotovo!"
                                    DownloadStatus.FAILED -> "Zlyhalo"
                                    else -> "Sťahujem ${(activeDownload.progress * 100).toInt()}%"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium
                            )
                            if (activeDownload.status == DownloadStatus.DONE) {
                                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { activeDownload.progress },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).height(4.dp),
                            color = when (activeDownload.status) {
                                DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                                DownloadStatus.DONE -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.primary
                            },
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
                isDownloaded -> {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(14.dp))
                            Text("Stiahnuté", color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("Pozrieť stiahnuté →", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                }
                else -> {
                    TextButton(onClick = onDownload, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Outlined.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Stiahnuť", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// ─── Downloads Tab ─────────────────────────────────────────────────────────────

@Composable
fun DownloadsTab(
    state: UiState,
    onDelete: (DownloadedFile) -> Unit,
    onOpen: (DownloadedFile) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showClearConfirm by remember { mutableStateOf(false) }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Vymazať všetky stiahnuté") },
            text = { Text("Vymazať všetkých ${state.downloadedFiles.size} stiahnutých súborov zo zariadenia? Táto akcia je nevratná.") },
            confirmButton = {
                TextButton(onClick = { onClearAll(); showClearConfirm = false }) {
                    Text("Vymazať všetko", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("Zrušiť") } }
        )
    }

    if (state.downloadedFiles.isEmpty() && state.activeDownloads.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.FolderOpen, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(16.dp))
                Text("Zatiaľ žiadne stiahnuté", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Epizódy sa tu objavia po synchronizácii na pozadí",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 13.sp)
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (state.activeDownloads.isNotEmpty()) {
            item {
                Text("PREBIEHA", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
            items(state.activeDownloads.values.toList(), key = { "active-${it.episodeUrl}" }) { dl ->
                ActiveDownloadCard(dl)
            }
            item { Spacer(Modifier.height(4.dp)) }
        }

        if (state.downloadedFiles.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("DOKONČENÉ", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    TextButton(
                        onClick = { showClearConfirm = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "Vymazať všetko",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Vymazať všetko",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            items(state.downloadedFiles, key = { it.episodeUrl }) { file ->
                DownloadedFileCard(file = file, onOpen = onOpen, onDelete = onDelete)
            }
        }
    }
}

@Composable
fun ActiveDownloadCard(download: ActiveDownload) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(download.showName, color = MaterialTheme.colorScheme.primary,
                fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
            Spacer(Modifier.height(4.dp))
            Text(download.title, color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text(
                    when (download.status) {
                        DownloadStatus.RESOLVING -> "Spracúvam stream..."
                        DownloadStatus.DONE -> "Dokončené"
                        DownloadStatus.FAILED -> "Zlyhalo: ${download.errorMessage}"
                        else -> "${(download.progress * 100).toInt()}% stiahnuté"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
                )
                CircularProgressIndicator(
                    progress = { download.progress },
                    modifier = Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeWidth = 3.dp
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { download.progress },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

@Composable
fun DownloadedFileCard(
    file: DownloadedFile,
    onOpen: (DownloadedFile) -> Unit,
    onDelete: (DownloadedFile) -> Unit
) {
    var showConfirm by remember { mutableStateOf(false) }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Vymazať epizódu") },
            text = { Text("Vymazať \"${file.title}\" zo zariadenia?") },
            confirmButton = {
                TextButton(onClick = { onDelete(file); showConfirm = false }) {
                    Text("Vymazať", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Zrušiť") } }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    if (file.date.isNotEmpty()) {
                        Text(file.date, color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                    }
                    Text(file.title, color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(formatFileSize(file.fileSizeBytes), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onOpen(file) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Otvoriť", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = { showConfirm = true },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Outlined.Delete, null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// ─── Settings Tab ──────────────────────────────────────────────────────────────

@Composable
fun SettingsTab(
    state: UiState,
    onIntervalChange: (Int) -> Unit,
    onAutoDownloadToggle: (Boolean) -> Unit,
    onWifiOnlyToggle: (Boolean) -> Unit,
    onShowToggle: (String, Boolean) -> Unit,
    onStvrShowToggle: (String, Boolean) -> Unit,
    onPrehrajEmailChange: (String) -> Unit,
    onPrehrajPasswordChange: (String) -> Unit,
    onPrehrajLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Auto-download master toggle
        item {
            SettingSection(title = "Synchronizácia na pozadí") {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Automatické sťahovanie", color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("Automaticky sťahovať nové epizódy na pozadí",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Switch(
                            checked = state.autoDownloadEnabled,
                            onCheckedChange = onAutoDownloadToggle,
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MaterialTheme.colorScheme.primary)
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Iba cez Wi-Fi", color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(
                                if (state.wifiOnlyDownload)
                                    "Sťahovať iba pri pripojení na Wi-Fi"
                                else
                                    "Sťahovať cez Wi-Fi aj mobilné dáta (5G/4G)",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = state.wifiOnlyDownload,
                            onCheckedChange = onWifiOnlyToggle,
                            enabled = state.autoDownloadEnabled,
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }

        // Sync interval selector
        item {
            SettingSection(title = "Interval kontroly") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Kontrolovať každých N hodín", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppSettings.INTERVAL_OPTIONS.forEach { hours ->
                            val selected = hours == state.syncIntervalHours
                            FilterChip(
                                selected = selected,
                                enabled = state.autoDownloadEnabled,
                                onClick = { onIntervalChange(hours) },
                                label = {
                                    Text(
                                        if (hours == 1) "1h" else "${hours}h",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                    if (state.autoDownloadEnabled) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Aktuálne nastavené na každú ${state.syncIntervalHours}. hodinu",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Per-show toggles
        item {
            SettingSection(title = "Relácie na sťahovanie") {
                Column {
                    TA3_SHOWS.forEachIndexed { index, show ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(show.displayName, color = MaterialTheme.colorScheme.onBackground,
                                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(show.url, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Switch(
                                checked = state.showEnabledMap[show.name] ?: true,
                                onCheckedChange = { onShowToggle(show.name, it) },
                                enabled = state.autoDownloadEnabled,
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MaterialTheme.colorScheme.primary)
                            )
                        }
                        if (index < TA3_SHOWS.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                            )
                        }
                    }
                }
            }
        }

        // STVR Per-show toggles
        item {
            SettingSection(title = "STVR relácie na sťahovanie") {
                Column {
                    STVR_SHOWS.forEachIndexed { index, show ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(show.displayName, color = MaterialTheme.colorScheme.onBackground,
                                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(show.url, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Switch(
                                checked = state.stvrShowEnabledMap[show.name] ?: true,
                                onCheckedChange = { onStvrShowToggle(show.name, it) },
                                enabled = state.autoDownloadEnabled,
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MaterialTheme.colorScheme.primary)
                            )
                        }
                        if (index < STVR_SHOWS.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                            )
                        }
                    }
                }
            }
        }

        // Prehraj.to account
        item {
            var showPass by remember { mutableStateOf(false) }
            SettingSection(title = "Prehraj.to Účet") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {

                    // Email field
                    OutlinedTextField(
                        value = state.prehrajEmail,
                        onValueChange = onPrehrajEmailChange,
                        label = { Text("E-mail", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        )
                    )

                    // Password field
                    OutlinedTextField(
                        value = state.prehrajPassword,
                        onValueChange = onPrehrajPasswordChange,
                        label = { Text("Heslo", fontSize = 13.sp) },
                        singleLine = true,
                        visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPass = !showPass }) {
                                Icon(
                                    if (showPass) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = if (showPass) "Skryť" else "Zobraziť"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onPrehrajLogin() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        )
                    )

                    // Login status + button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when (state.prehrajLoginStatus) {
                            PrehrajLoginStatus.LOGGED_IN ->
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Default.CheckCircle, null,
                                        tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(14.dp))
                                    Text("Prihlásený", color = MaterialTheme.colorScheme.secondary,
                                        fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            PrehrajLoginStatus.LOGGING_IN ->
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                                    Text("Prihlasujem…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                }
                            PrehrajLoginStatus.FAILED ->
                                Text(state.prehrajLoginError ?: "Prihlásenie zlyhalo",
                                    color = MaterialTheme.colorScheme.error, fontSize = 11.sp,
                                    modifier = Modifier.weight(1f))
                            else -> Spacer(Modifier.weight(1f))
                        }
                        Button(
                            onClick = onPrehrajLogin,
                            enabled = state.prehrajLoginStatus != PrehrajLoginStatus.LOGGING_IN,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Prihlásiť sa", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // Info box
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
            ) {
                Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text(
                        "Synchronizácia na pozadí kontroluje dnešné a včerajšie epizódy. " +
                        "Stiahnuté súbory je možné otvoriť pomocou VLC, MX Player alebo inej mediálnej aplikácie.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title.uppercase(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            content()
        }
    }
}

// ─── Active Downloads Banner ───────────────────────────────────────────────────

@Composable
fun ActiveDownloadsBanner(downloads: List<ActiveDownload>, modifier: Modifier = Modifier) {
    val inProgress = downloads.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.RESOLVING }
    if (inProgress.isEmpty()) return

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.5.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text("${inProgress.size} stiahnutí prebieha",
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(inProgress.first().title,
                    color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ─── Prehraj.to Tab ────────────────────────────────────────────────────────────

@Composable
fun PrehrajTab(
    state: UiState,
    onSearchQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onExtractUrl: (PrehrajMovie) -> Unit,
    onDownload: (PrehrajMovie, String) -> Unit,
    onCancelDownload: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxSize()) {

        // Login status banner
        if (state.prehrajLoginStatus != PrehrajLoginStatus.LOGGED_IN) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (state.prehrajLoginStatus == PrehrajLoginStatus.FAILED)
                            MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (state.prehrajLoginStatus == PrehrajLoginStatus.LOGGING_IN)
                        Icons.Outlined.HourglassEmpty
                    else Icons.Outlined.Warning,
                    null,
                    tint = if (state.prehrajLoginStatus == PrehrajLoginStatus.FAILED)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    when (state.prehrajLoginStatus) {
                        PrehrajLoginStatus.LOGGING_IN -> "Prihlasujem na prehraj.to…"
                        PrehrajLoginStatus.FAILED -> state.prehrajLoginError ?: "Prihlásenie zlyhalo — prejdite do Nastavení"
                        else -> "Neprihlásený — pridajte údaje v Nastaveniach"
                    },
                    fontSize = 12.sp,
                    color = if (state.prehrajLoginStatus == PrehrajLoginStatus.FAILED)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Search bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = state.prehrajSearchQuery,
                    onValueChange = onSearchQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onBackground),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboard?.hide()
                        onSearch()
                    }),
                    decorationBox = { inner ->
                        if (state.prehrajSearchQuery.isEmpty()) {
                            Text("Hľadať filmy (napr. Spider Man)…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                        inner()
                    }
                )
                if (state.prehrajSearchQuery.isNotEmpty()) {
                    Icon(Icons.Default.Close, "Vymazať",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp).clickable { onSearchQueryChange("") })
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { keyboard?.hide(); onSearch() }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("Hľadať", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Content
        when {
            state.prehrajSearching -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text("Prehľadávam prehraj.to…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            state.prehrajSearchError != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ErrorOutline, null,
                            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(state.prehrajSearchError, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp)
                    }
                }
            }
            state.prehrajSearchResults.isEmpty() && state.prehrajSearchQuery.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Movie, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(72.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Vyhľadať film", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("Tu sa zobrazia výsledky z prehraj.to",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontSize = 13.sp)
                    }
                }
            }
            state.prehrajSearchResults.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Žiadne výsledky", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.prehrajSearchResults, key = { it.pageUrl }) { movie ->
                        val resolvedUrl = state.prehrajResolvedUrls[movie.pageUrl]
                        PrehrajMovieCard(
                            movie = movie,
                            activeDownload = state.activeDownloads[movie.pageUrl],
                            isDownloaded = state.downloadedFiles.any { it.episodeUrl == movie.pageUrl },
                            resolvedUrl = resolvedUrl,
                            onExtractUrl = { onExtractUrl(movie) },
                            onDownload = { url -> onDownload(movie, url) },
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
    }
}

@Composable
fun PrehrajMovieCard(
    movie: PrehrajMovie,
    activeDownload: ActiveDownload?,
    isDownloaded: Boolean,
    resolvedUrl: String?,
    onExtractUrl: () -> Unit,
    onDownload: (String) -> Unit,
    onShare: (String) -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    if (movie.year.isNotEmpty()) {
                        Text(movie.year, color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        Spacer(Modifier.height(2.dp))
                    }
                    Text(movie.title, color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Outlined.Movie, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.size(24.dp))
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Spacer(Modifier.height(10.dp))

            when {
                activeDownload != null -> {
                    Column {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Text(
                                when (activeDownload.status) {
                                    DownloadStatus.RESOLVING -> "Získavam URL videa…"
                                    DownloadStatus.DONE -> "Hotovo!"
                                    DownloadStatus.FAILED -> "Zlyhalo: ${activeDownload.errorMessage}"
                                    else -> "Sťahujem ${(activeDownload.progress * 100).toInt()}%"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp, fontWeight = FontWeight.Medium
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (activeDownload.status == DownloadStatus.DONE) {
                                    Icon(Icons.Default.CheckCircle, null,
                                        tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                                } else if (activeDownload.status == DownloadStatus.DOWNLOADING || activeDownload.status == DownloadStatus.RESOLVING) {
                                    IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Default.Close, "Zrušiť", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { activeDownload.progress },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).height(4.dp),
                            color = when (activeDownload.status) {
                                DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                                DownloadStatus.DONE -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.primary
                            },
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
                isDownloaded -> {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, null,
                                tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(14.dp))
                            Text("Stiahnuté", color = MaterialTheme.colorScheme.secondary,
                                fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("Pozrieť stiahnuté →", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                }
                resolvedUrl != null -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { onShare(resolvedUrl) }, contentPadding = PaddingValues(0.dp)) {
                            Icon(Icons.Outlined.Share, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Zdieľať", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Spacer(Modifier.width(16.dp))
                        TextButton(onClick = { onDownload(resolvedUrl) }, contentPadding = PaddingValues(0.dp)) {
                            Icon(Icons.Outlined.Download, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Stiahnuť", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
                else -> {
                    TextButton(onClick = onExtractUrl, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Outlined.Link, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Získať URL", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
