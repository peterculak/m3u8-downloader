package com.ta3.downloader.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ta3.downloader.*
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.foundation.interaction.MutableInteractionSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(
    state: UiState,
    onIntervalChange: (Int) -> Unit,
    onAutoDownloadToggle: (Boolean) -> Unit,
    onAutoDeleteToggle: (Boolean) -> Unit,
    onAutoDeleteDaysChange: (Int) -> Unit,
    onMinVideoDurationChange: (String, Int) -> Unit,
    onManualCleanup: () -> Unit,
    onWifiOnlyToggle: (Boolean) -> Unit,
    onShowToggle: (String, Boolean) -> Unit,
    onStvrShowToggle: (String, Boolean) -> Unit,
    onYtChannelToggle: (String, Boolean) -> Unit,
    onPrehrajEmailChange: (String) -> Unit,
    onPrehrajPasswordChange: (String) -> Unit,
    onPrehrajLogin: () -> Unit,
    onAddCustomChannel: (String, String) -> Unit,
    onEditCustomChannel: (String, String, String) -> Unit,
    onRemoveCustomChannel: (String) -> Unit,
    onCustomChannelUrlChange: (String) -> Unit,
    onCustomChannelNameChange: (String) -> Unit,
    onMoveTa3Show: (Int, Int) -> Unit,
    onMoveStvrShow: (Int, Int) -> Unit,
    onMoveTyzdenShow: (Int, Int) -> Unit,
    onMoveYtChannel: (Int, Int) -> Unit,
    onMoveSection: (Int, Int) -> Unit,
    onToggleSection: (String) -> Unit,
    onToggleItem: (String) -> Unit,
    onTyzdenShowToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showEditDialog by remember { mutableStateOf<YouTubeChannel?>(null) }
    
    val list = remember(state) {
        val items = mutableListOf<SettingsRow>()
        items.add(SettingsRow.Static("system_settings"))
        items.add(SettingsRow.Static("storage_settings"))

        state.sectionOrder.forEach { sectionId ->
            when (sectionId) {
                "prehraj" -> {
                    items.add(SettingsRow.SectionHeader("prehraj", "PREHRAJ", state.expandedSections.contains("prehraj")))
                    if (state.expandedSections.contains("prehraj")) {
                        items.add(SettingsRow.PrehrajSettingsItem(state.expandedSections.contains("prehraj")))
                    }
                }
                "ta3" -> {
                    items.add(SettingsRow.SectionHeader("ta3", "TA3", state.expandedSections.contains("ta3")))
                    if (state.expandedSections.contains("ta3")) {
                        state.ta3Shows.forEach { items.add(SettingsRow.Ta3ShowItem(it, state.expandedItems.contains("ta3_${it.name}"))) }
                    }
                }
                "stvr" -> {
                    items.add(SettingsRow.SectionHeader("stvr", "STVR", state.expandedSections.contains("stvr")))
                    if (state.expandedSections.contains("stvr")) {
                        state.stvrShows.forEach { items.add(SettingsRow.StvrShowItem(it, state.expandedItems.contains("stvr_${it.name}"))) }
                    }
                }
                "tyzden" -> {
                    items.add(SettingsRow.SectionHeader("tyzden", ".TÝŽDEŇ", state.expandedSections.contains("tyzden")))
                    if (state.expandedSections.contains("tyzden")) {
                        state.tyzdenShows.forEach { items.add(SettingsRow.TyzdenShowItem(it, state.expandedItems.contains("tyzden_${it.name}"))) }
                    }
                }
                "yt" -> {
                    items.add(SettingsRow.SectionHeader("yt", "YOUTUBE", state.expandedSections.contains("yt")))
                    if (state.expandedSections.contains("yt")) {
                        state.allYtChannels.forEach { items.add(SettingsRow.YtChannelItem(it, state.expandedItems.contains("yt_${it.name}"))) }
                    }
                }
            }
        }

        items.add(SettingsRow.Static("add_yt"))
                items.add(SettingsRow.Static("info_box"))
        items
    }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromItem = list.getOrNull(from.index)
        val toItem = list.getOrNull(to.index)
        if (fromItem == null || toItem == null) return@rememberReorderableLazyListState

        // Only allow reordering within the same type
        if (fromItem::class != toItem::class) return@rememberReorderableLazyListState

        when (fromItem) {
            is SettingsRow.SectionHeader -> {
                // Find actual indices in the sectionOrder list
                val fromIdx = state.sectionOrder.indexOf(fromItem.sectionId)
                val toIdx = state.sectionOrder.indexOf((toItem as SettingsRow.SectionHeader).sectionId)
                if (fromIdx >= 0 && toIdx >= 0) onMoveSection(fromIdx, toIdx)
            }

            is SettingsRow.Ta3ShowItem -> {
                val fromIdx = state.ta3Shows.indexOf(fromItem.show)
                val toIdx = state.ta3Shows.indexOf((toItem as SettingsRow.Ta3ShowItem).show)
                if (fromIdx >= 0 && toIdx >= 0) onMoveTa3Show(fromIdx, toIdx)
            }
            is SettingsRow.StvrShowItem -> {
                val fromIdx = state.stvrShows.indexOf(fromItem.show)
                val toIdx = state.stvrShows.indexOf((toItem as SettingsRow.StvrShowItem).show)
                if (fromIdx >= 0 && toIdx >= 0) onMoveStvrShow(fromIdx, toIdx)
            }
            is SettingsRow.TyzdenShowItem -> {
                val fromIdx = state.tyzdenShows.indexOf(fromItem.show)
                val toIdx = state.tyzdenShows.indexOf((toItem as SettingsRow.TyzdenShowItem).show)
                if (fromIdx >= 0 && toIdx >= 0) onMoveTyzdenShow(fromIdx, toIdx)
            }
            is SettingsRow.YtChannelItem -> {
                val fromIdx = state.allYtChannels.indexOf(fromItem.channel)
                val toIdx = state.allYtChannels.indexOf((toItem as SettingsRow.YtChannelItem).channel)
                if (fromIdx >= 0 && toIdx >= 0) onMoveYtChannel(fromIdx, toIdx)
            }
            else -> {}
        }
    }

    LazyColumn(
        state = lazyListState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(list, key = { it.rowKey }) { row ->
            ReorderableItem(reorderableState, key = row.rowKey) { isDragging ->
                val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp)
                val shape = RoundedCornerShape(12.dp)
                val modifierBase = Modifier
                    .fillMaxWidth()
                    .shadow(elevation, shape, clip = false)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surface)

                when (row) {
                    is SettingsRow.Static -> {
                        Box(modifier = modifierBase) {
                            RenderStaticSection(
                                id = row.id,
                                state = state,
                                onAutoDownloadToggle = onAutoDownloadToggle,
                                onWifiOnlyToggle = onWifiOnlyToggle,
                                onIntervalChange = onIntervalChange,
                                onAutoDeleteToggle = onAutoDeleteToggle,
                                onAutoDeleteDaysChange = onAutoDeleteDaysChange,
                                onManualCleanup = onManualCleanup,
                                onCustomChannelUrlChange = onCustomChannelUrlChange,
                                onCustomChannelNameChange = onCustomChannelNameChange,
                                onAddCustomChannel = onAddCustomChannel,
                                onPrehrajEmailChange = onPrehrajEmailChange,
                                onPrehrajPasswordChange = onPrehrajPasswordChange,
                                onPrehrajLogin = onPrehrajLogin
                            )
                        }
                    }
                    is SettingsRow.SectionHeader -> {
                        Box(modifier = modifierBase) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleSection(row.sectionId) }
                                    .padding(vertical = 12.dp, horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.DragHandle,
                                        contentDescription = "Drag",
                                        modifier = Modifier.draggableHandle().padding(end = 12.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = row.title.uppercase(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                }
                                Icon(
                                    imageVector = if (row.isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    is SettingsRow.PrehrajSettingsItem -> {
                        Box(modifier = modifierBase) {
                            RenderStaticSection(
                                id = "prehraj_settings",
                                state = state,
                                onAutoDownloadToggle = onAutoDownloadToggle,
                                onWifiOnlyToggle = onWifiOnlyToggle,
                                onIntervalChange = onIntervalChange,
                                onAutoDeleteToggle = onAutoDeleteToggle,
                                onAutoDeleteDaysChange = onAutoDeleteDaysChange,
                                onManualCleanup = onManualCleanup,
                                onCustomChannelUrlChange = onCustomChannelUrlChange,
                                onCustomChannelNameChange = onCustomChannelNameChange,
                                onAddCustomChannel = onAddCustomChannel,
                                onPrehrajEmailChange = onPrehrajEmailChange,
                                onPrehrajPasswordChange = onPrehrajPasswordChange,
                                onPrehrajLogin = onPrehrajLogin
                            )
                        }
                    }
                    is SettingsRow.Ta3ShowItem -> {
                        Box(modifier = modifierBase) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleItem(row.rowKey) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(row.show.displayName, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                    if (row.isExpanded) {
                                        Text(row.show.url, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (row.isExpanded) {
                                        Switch(
                                            checked = state.showEnabledMap[row.show.name] ?: true,
                                            onCheckedChange = { onShowToggle(row.show.name, it) },
                                            enabled = state.autoDownloadEnabled,
                                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MaterialTheme.colorScheme.primary)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.DragHandle,
                                            contentDescription = "Drag",
                                            modifier = Modifier.draggableHandle(),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                    is SettingsRow.StvrShowItem -> {
                        Box(modifier = modifierBase) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleItem(row.rowKey) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(row.show.displayName, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                    if (row.isExpanded) {
                                        Text(row.show.url, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (row.isExpanded) {
                                        Switch(
                                            checked = state.stvrShowEnabledMap[row.show.name] ?: true,
                                            onCheckedChange = { onStvrShowToggle(row.show.name, it) },
                                            enabled = state.autoDownloadEnabled,
                                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MaterialTheme.colorScheme.primary)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.DragHandle,
                                            contentDescription = "Drag",
                                            modifier = Modifier.draggableHandle(),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                    is SettingsRow.TyzdenShowItem -> {
                        Box(modifier = modifierBase) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleItem(row.rowKey) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(row.show.displayName, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                    if (row.isExpanded) {
                                        Text(row.show.url, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (row.isExpanded) {
                                        Switch(
                                            checked = state.tyzdenShowEnabledMap[row.show.name] ?: true,
                                            onCheckedChange = { onTyzdenShowToggle(row.show.name, it) },
                                            enabled = state.autoDownloadEnabled,
                                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MaterialTheme.colorScheme.primary)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.DragHandle,
                                            contentDescription = "Drag",
                                            modifier = Modifier.draggableHandle(),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                    is SettingsRow.YtChannelItem -> {
                        Box(modifier = modifierBase) {
                            Column(modifier = Modifier.fillMaxWidth().clickable { onToggleItem(row.rowKey) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(row.channel.displayName, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                        if (row.isExpanded) {
                                            Text(row.channel.channelUrl, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (row.isExpanded) {
                                            Switch(
                                                checked = state.ytChannelEnabledMap[row.channel.name] ?: true,
                                                onCheckedChange = { onYtChannelToggle(row.channel.name, it) },
                                                enabled = state.autoDownloadEnabled,
                                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MaterialTheme.colorScheme.primary)
                                            )
                                            if (YOUTUBE_CHANNELS.none { it.name == row.channel.name }) {
                                                IconButton(onClick = { showEditDialog = row.channel }) {
                                                    Icon(Icons.Default.Edit, contentDescription = "Edit channel", tint = MaterialTheme.colorScheme.primary)
                                                }
                                                IconButton(onClick = { onRemoveCustomChannel(row.channel.name) }) {
                                                    Icon(Icons.Default.Delete, contentDescription = "Delete channel", tint = MaterialTheme.colorScheme.error)
                                                }
                                            }
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.DragHandle,
                                                contentDescription = "Drag",
                                                modifier = Modifier.draggableHandle(),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                if (row.isExpanded) {
                                    Spacer(Modifier.height(8.dp))
                                    Text("Minimálna dĺžka videa", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                    Spacer(Modifier.height(8.dp))
                                    @OptIn(ExperimentalLayoutApi::class)
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        AppSettings.MIN_DURATION_OPTIONS.forEach { minutes ->
                                            val selected = minutes == (state.showMinDurationMap[row.channel.name] ?: 0)
                                            FilterChip(
                                                selected = selected,
                                                enabled = state.autoDownloadEnabled && (state.ytChannelEnabledMap[row.channel.name] ?: true),
                                                onClick = { onMinVideoDurationChange(row.channel.name, minutes) },
                                                label = {
                                                    Text(
                                                        if (minutes == 0) "Všetky" else "$minutes min",
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
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditDialog != null) {
        val channelToEdit = showEditDialog!!
        var editUrl by remember(channelToEdit) { mutableStateOf(channelToEdit.channelUrl) }
        var editName by remember(channelToEdit) { mutableStateOf(channelToEdit.displayName) }

        AlertDialog(
            onDismissRequest = { showEditDialog = null },
            title = { Text("Upraviť kanál") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editUrl,
                        onValueChange = { editUrl = it },
                        label = { Text("URL kanálu") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Zobrazovaný názov") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onEditCustomChannel(channelToEdit.name, editUrl, editName)
                        showEditDialog = null
                    },
                    enabled = editUrl.isNotBlank() && editName.isNotBlank()
                ) { Text("Uložiť") }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = null }) { Text("Zrušiť") }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RenderStaticSection(
    id: String,
    state: UiState,
    onAutoDownloadToggle: (Boolean) -> Unit,
    onWifiOnlyToggle: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onAutoDeleteToggle: (Boolean) -> Unit,
    onAutoDeleteDaysChange: (Int) -> Unit,
    onManualCleanup: () -> Unit,
    onCustomChannelUrlChange: (String) -> Unit,
    onCustomChannelNameChange: (String) -> Unit,
    onAddCustomChannel: (String, String) -> Unit,
    onPrehrajEmailChange: (String) -> Unit,
    onPrehrajPasswordChange: (String) -> Unit,
    onPrehrajLogin: () -> Unit
) {
    when (id) {
        "system_settings" -> {
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Automatické sťahovanie", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("Automaticky sťahovať nové epizódy na pozadí", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Switch(checked = state.autoDownloadEnabled, onCheckedChange = onAutoDownloadToggle)
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Iba cez Wi-Fi", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(if (state.wifiOnlyDownload) "Sťahovať iba pri pripojení na Wi-Fi" else "Sťahovať cez Wi-Fi aj mobilné dáta", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Switch(checked = state.wifiOnlyDownload, onCheckedChange = onWifiOnlyToggle, enabled = state.autoDownloadEnabled)
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("Kontrolovať každých N hodín", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Spacer(Modifier.height(10.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppSettings.INTERVAL_OPTIONS.forEach { hours ->
                                FilterChip(
                                    selected = hours == state.syncIntervalHours,
                                    enabled = state.autoDownloadEnabled,
                                    onClick = { onIntervalChange(hours) },
                                    label = { Text("${hours}h", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                                )
                            }
                        }
                    }
                }
            }
        }
        "storage_settings" -> {
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column {
                    val totalVideos = state.downloadedFiles.size
                    val totalSize = formatFileSize(state.downloadedFiles.sumOf { it.fileSizeBytes })
                    
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Úložisko", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Spacer(Modifier.height(4.dp))
                            Text("$totalVideos stiahnutých epizód ($totalSize)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Automatické vymazávanie starých epizód", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        }
                        Switch(checked = state.autoDeleteEnabled, onCheckedChange = onAutoDeleteToggle)
                    }
                    if (state.autoDeleteEnabled) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                AppSettings.AUTO_DELETE_DAYS_OPTIONS.forEach { days ->
                                    FilterChip(
                                        selected = days == state.autoDeleteDays,
                                        onClick = { onAutoDeleteDaysChange(days) },
                                        label = { Text("$days dní", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = onManualCleanup, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Spustiť vymazanie hneď", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
        "add_yt" -> {
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Pridať vlastný YouTube kanál", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = state.customChannelUrlInput, onValueChange = onCustomChannelUrlChange, label = { Text("URL kanálu") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = state.customChannelNameInput, onValueChange = onCustomChannelNameChange, label = { Text("Zobrazovaný názov") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { onAddCustomChannel(state.customChannelUrlInput, state.customChannelNameInput) }, enabled = state.customChannelUrlInput.isNotBlank() && state.customChannelNameInput.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text("Pridať kanál")
                    }
                }
            }
        }
        "prehraj_settings" -> {
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(value = state.prehrajEmail, onValueChange = onPrehrajEmailChange, label = { Text("E-mail") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = state.prehrajPassword, onValueChange = onPrehrajPasswordChange, label = { Text("Heslo") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onPrehrajLogin, modifier = Modifier.fillMaxWidth()) {
                        Text("Prihlásiť sa")
                    }
                }
            }
        }
        "info_box" -> {
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))) {
                Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text("Synchronizácia na pozadí kontroluje dnešné a včerajšie epizódy.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}
