package com.ta3.downloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.font.FontWeight
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
                    text = "Slovak TV archive",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                Spacer(Modifier.height(12.dp))

                // 3-tab bar: Episodes | Downloads | Settings
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    TabItem(
                        icon = Icons.Outlined.PlayCircle,
                        label = "Episodes",
                        selected = state.selectedTab == Tab.EPISODES,
                        onClick = { viewModel.selectTab(Tab.EPISODES) },
                        modifier = Modifier.weight(1f)
                    )
                    TabItem(
                        icon = Icons.Outlined.FolderOpen,
                        label = "Downloads (${state.downloadedFiles.size})",
                        selected = state.selectedTab == Tab.DOWNLOADS,
                        onClick = { viewModel.selectTab(Tab.DOWNLOADS) },
                        modifier = Modifier.weight(1.3f)
                    )
                    TabItem(
                        icon = Icons.Outlined.Settings,
                        label = "Settings",
                        selected = state.selectedTab == Tab.SETTINGS,
                        onClick = { viewModel.selectTab(Tab.SETTINGS) },
                        modifier = Modifier.weight(1f)
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
            Tab.DOWNLOADS -> DownloadsTab(
                state = state,
                onDelete = { viewModel.deleteDownload(it.episodeUrl) },
                onOpen = { openFileWithPlayer(context, it) },
                modifier = Modifier.padding(innerPadding)
            )
            Tab.SETTINGS -> SettingsTab(
                state = state,
                onIntervalChange = { viewModel.setSyncInterval(it) },
                onAutoDownloadToggle = { viewModel.setAutoDownloadEnabled(it) },
                onShowToggle = { name, enabled -> viewModel.setShowEnabled(name, enabled) },
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
            .padding(vertical = 8.dp, horizontal = 4.dp),
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
                            Text("Search episodes...", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                        inner()
                    }
                )
                if (state.searchQuery.isNotEmpty()) {
                    Icon(Icons.Default.Close, "Clear",
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
                        Text("Fetching episodes...", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        Button(onClick = { viewModel.fetchEpisodes() }) { Text("Retry") }
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
                                Text("No episodes found", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    DownloadStatus.RESOLVING -> "Resolving stream..."
                                    DownloadStatus.DONE -> "Done!"
                                    DownloadStatus.FAILED -> "Failed"
                                    else -> "Downloading ${(activeDownload.progress * 100).toInt()}%"
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
                            Text("Downloaded", color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("See Downloads →", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                }
                else -> {
                    TextButton(onClick = onDownload, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Outlined.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Download", fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
    modifier: Modifier = Modifier
) {
    if (state.downloadedFiles.isEmpty() && state.activeDownloads.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.FolderOpen, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(16.dp))
                Text("No downloads yet", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Episodes will appear here after background sync",
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
                Text("IN PROGRESS", color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                Text("COMPLETED", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 4.dp))
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
                        DownloadStatus.RESOLVING -> "Resolving stream..."
                        DownloadStatus.DONE -> "Complete"
                        DownloadStatus.FAILED -> "Failed: ${download.errorMessage}"
                        else -> "${(download.progress * 100).toInt()}% downloaded"
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
            title = { Text("Delete Episode") },
            text = { Text("Delete \"${file.title}\" from your device?") },
            confirmButton = {
                TextButton(onClick = { onDelete(file); showConfirm = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel") } }
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
                    Text("Open", fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
    onShowToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Auto-download master toggle
        item {
            SettingSection(title = "Background Sync") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-download", color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text("Automatically download new episodes in background",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Switch(
                        checked = state.autoDownloadEnabled,
                        onCheckedChange = onAutoDownloadToggle,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }

        // Sync interval selector
        item {
            SettingSection(title = "Check Interval") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Check every N hours", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
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
                            "Currently set to every ${state.syncIntervalHours} hour${if (state.syncIntervalHours == 1) "" else "s"}",
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
            SettingSection(title = "Shows to Download") {
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
                        "Background sync checks for today's and yesterday's episodes. " +
                        "Downloaded files can be opened with VLC, MX Player, or any media app.",
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
                Text("${inProgress.size} download${if (inProgress.size > 1) "s" else ""} in progress",
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(inProgress.first().title,
                    color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
