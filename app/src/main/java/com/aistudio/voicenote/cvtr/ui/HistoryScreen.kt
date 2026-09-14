package com.aistudio.voicenote.cvtr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.aistudio.voicenote.cvtr.audio.PlaybackState
import com.aistudio.voicenote.cvtr.data.local.ConversionHistory
import com.aistudio.voicenote.cvtr.data.local.DeliveryStatus
import com.aistudio.voicenote.cvtr.audio.WaveformCodec
import com.aistudio.voicenote.cvtr.ui.components.WaveformVisualizer
import com.aistudio.voicenote.cvtr.ui.theme.AccentCoral
import com.aistudio.voicenote.cvtr.ui.theme.AccentRoyalBlue
import com.aistudio.voicenote.cvtr.ui.theme.AppCanvasBackground
import com.aistudio.voicenote.cvtr.ui.theme.CardSurfaceWhite
import com.aistudio.voicenote.cvtr.ui.theme.DeepNavyDisplay
import com.aistudio.voicenote.cvtr.ui.theme.PastelMintCardBg
import com.aistudio.voicenote.cvtr.ui.theme.PastelMintText
import com.aistudio.voicenote.cvtr.ui.theme.PastelPeachCardBg
import com.aistudio.voicenote.cvtr.ui.theme.PastelPeriwinkleCardBg
import com.aistudio.voicenote.cvtr.ui.theme.SubtitleSlate
import com.aistudio.voicenote.cvtr.ui.theme.SubtleBorder
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    modifier: Modifier = Modifier,
    onStartConversion: () -> Unit = {},
    onEditHistory: (Long) -> Unit = {},
    bottomOverlayClearance: Dp = 0.dp,
    statusBarInset: Dp? = null
) {
    val historyItems = viewModel.historyItems.collectAsLazyPagingItems()
    val totalHistoryCount by viewModel.totalHistoryCount.collectAsStateWithLifecycle()
    val totalHistoryBytes by viewModel.totalHistoryBytes.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val historyFilter by viewModel.historyFilter.collectAsStateWithLifecycle()
    val historySort by viewModel.historySort.collectAsStateWithLifecycle()
    val currentlyPlayingId by viewModel.currentlyPlayingId.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val safeTopInset = statusBarInset
        ?: WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val snackbarHostState = remember { SnackbarHostState() }
    val displayNow = remember { System.currentTimeMillis() }
    var pendingDeleteItems by remember { mutableStateOf<List<ConversionHistory>>(emptyList()) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var showCleanupDialog by remember { mutableStateOf(false) }
    var collapsedSections by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectedItems = historyItems.itemSnapshotList.items.filter { it.id in selectedIds }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(historyItems.itemSnapshotList.items) {
        val loadedIds = historyItems.itemSnapshotList.items.map { it.id }.toSet()
        selectedIds = selectedIds.intersect(loadedIds)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.pausePlayback()
    }

    if (pendingDeleteItems.isNotEmpty()) {
        DeleteConfirmationDialog(
            items = pendingDeleteItems,
            onDismiss = { pendingDeleteItems = emptyList() },
            onConfirm = {
                if (pendingDeleteItems.size == 1) {
                    viewModel.deleteItem(pendingDeleteItems.single())
                } else {
                    viewModel.deleteItems(pendingDeleteItems)
                }
                pendingDeleteItems = emptyList()
                selectedIds = emptySet()
                isSelectionMode = false
            }
        )
    }
    if (showCleanupDialog) {
        CleanupConfirmationDialog(
            onDismiss = { showCleanupDialog = false },
            onConfirm = { days ->
                showCleanupDialog = false
                viewModel.cleanupOlderThan(days)
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppCanvasBackground)
    ) {
        if (totalHistoryCount == 0 && historyItems.itemCount == 0) {
            EmptyHistoryState(
                modifier = Modifier.fillMaxSize(),
                onStartConversion = onStartConversion
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("history_list"),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    top = 24.dp + safeTopInset,
                    end = 20.dp,
                    bottom = 24.dp + bottomOverlayClearance +
                        if (selectedItems.isNotEmpty()) 72.dp else 0.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "history-header") {
                    if (isSelectionMode) {
                        SelectionHeader(
                            selectedCount = selectedIds.size,
                            onCancel = {
                                selectedIds = emptySet()
                                isSelectionMode = false
                            }
                        )
                    } else {
                        HistoryHeader(
                            total = totalHistoryCount,
                            totalBytes = totalHistoryBytes,
                            onSelectFiles = { isSelectionMode = true },
                            onCleanupOld = { showCleanupDialog = true }
                        )
                    }
                }
                item(key = "history-controls") {
                    HistoryControls(
                        query = searchQuery,
                        filter = historyFilter,
                        sort = historySort,
                        onQueryChange = viewModel::updateSearch,
                        onFilterChange = viewModel::setFilter,
                        onSortChange = viewModel::setSort
                    )
                }
                if (historyItems.itemCount == 0) {
                    item(key = "history-no-results") {
                        NoHistoryResultsState()
                    }
                } else {
                    items(
                        count = historyItems.itemCount,
                        key = { index ->
                            historyItems.peek(index)?.id ?: "history-placeholder-$index"
                        }
                    ) { index ->
                        val item = historyItems[index] ?: return@items
                        val usesDateSections = historySort == HistorySort.NEWEST ||
                            historySort == HistorySort.OLDEST
                        val sectionTitle = if (usesDateSections) {
                            historySectionTitle(item.createdAt, displayNow)
                        } else {
                            ""
                        }
                        val previousItem = if (!usesDateSections || index == 0) {
                            null
                        } else {
                            historyItems.peek(index - 1)
                        }
                        val startsSection = usesDateSections && (
                            previousItem == null ||
                                historySectionTitle(previousItem.createdAt, displayNow) != sectionTitle
                            )
                        val expanded = !usesDateSections || sectionTitle !in collapsedSections
                        Column {
                            if (startsSection) {
                                HistorySectionHeader(
                                    title = sectionTitle,
                                    count = null,
                                    expanded = expanded,
                                    onToggle = {
                                        collapsedSections = if (expanded) {
                                            collapsedSections + sectionTitle
                                        } else {
                                            collapsedSections - sectionTitle
                                        }
                                    }
                                )
                            }
                            if (expanded) {
                                HistoryItemCard(
                                    item = item,
                                    sectionTitle = sectionTitle,
                                    isCurrent = currentlyPlayingId == item.id,
                                    playbackStateFlow = viewModel.playbackState,
                                    selectionMode = isSelectionMode,
                                    selected = item.id in selectedIds,
                                    onPlayPause = { viewModel.playPause(item) },
                                    onShare = { viewModel.shareItem(item) },
                                    onConfirmSent = { viewModel.confirmSent(item) },
                                    onEdit = { onEditHistory(item.id) },
                                    onDelete = { pendingDeleteItems = listOf(item) },
                                    onToggleSelection = {
                                        isSelectionMode = true
                                        selectedIds = if (item.id in selectedIds) {
                                            selectedIds - item.id
                                        } else {
                                            selectedIds + item.id
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (selectedItems.isNotEmpty()) {
            BulkSelectionBar(
                selectedCount = selectedItems.size,
                bottomPadding = bottomOverlayClearance,
                onDelete = { pendingDeleteItems = selectedItems }
            )
        }

        SnackbarHost(
            snackbarHostState,
            Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = bottomOverlayClearance +
                        if (selectedItems.isNotEmpty()) 72.dp else 0.dp
                )
        )
    }
}

@Composable
private fun HistoryControls(
    query: String,
    filter: HistoryFilter,
    sort: HistorySort,
    onQueryChange: (String) -> Unit,
    onFilterChange: (HistoryFilter) -> Unit,
    onSortChange: (HistorySort) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .testTag("history_search"),
                placeholder = { Text("Cari voice note...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (query.isNotBlank()) {
                    {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Hapus pencarian")
                        }
                    }
                } else {
                    null
                },
                singleLine = true
            )
            var sortMenuExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(
                    onClick = { sortMenuExpanded = true },
                    modifier = Modifier.testTag("history_sort")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Urutkan riwayat")
                }
                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false }
                ) {
                    HistorySort.values().forEach { option ->
                        DropdownMenuItem(
                            text = { Text(historySortLabel(option)) },
                            onClick = {
                                onSortChange(option)
                                sortMenuExpanded = false
                            },
                            trailingIcon = if (sort == option) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else {
                                null
                            }
                        )
                    }
                }
            }
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 2.dp)
        ) {
            items(HistoryFilter.values(), key = { it.name }) { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { onFilterChange(option) },
                    label = { Text(historyFilterLabel(option)) }
                )
            }
        }
    }
}

@Composable
private fun DeleteConfirmationDialog(
    items: List<ConversionHistory>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val isBulk = items.size > 1
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("history_delete_confirmation"),
            shape = RoundedCornerShape(24.dp),
            color = CardSurfaceWhite,
            tonalElevation = 0.dp,
            shadowElevation = 12.dp
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(PastelPeachCardBg, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = AccentCoral)
                    }
                    Text(
                        if (isBulk) "Hapus file terpilih?" else "Hapus voice note?",
                        style = MaterialTheme.typography.titleLarge,
                        color = DeepNavyDisplay,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    if (isBulk) {
                        "${items.size} file akan dihapus dari penyimpanan dan riwayat."
                    } else {
                        "${items.single().outputFileName} akan dihapus dari penyimpanan dan riwayat."
                    },
                    modifier = Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SubtitleSlate
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                ) {
                    TextButton(onClick = onDismiss) { Text("Batal") }
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCoral)
                    ) { Text("Hapus") }
                }
            }
        }
    }
}

@Composable
private fun CleanupConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var selectedDays by remember { mutableStateOf(30) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("history_cleanup_confirmation"),
            shape = RoundedCornerShape(24.dp),
            color = CardSurfaceWhite,
            shadowElevation = 12.dp
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                Text(
                    "Hapus hasil lama?",
                    style = MaterialTheme.typography.titleLarge,
                    color = DeepNavyDisplay,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Hasil yang lebih lama dari $selectedDays hari akan dihapus dari penyimpanan dan riwayat.",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SubtitleSlate
                )
                Text(
                    "Batas usia hasil",
                    modifier = Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = DeepNavyDisplay
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(7, 30, 90).forEach { days ->
                        FilterChip(
                            selected = selectedDays == days,
                            onClick = { selectedDays = days },
                            label = { Text("$days hari") }
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                ) {
                    TextButton(onClick = onDismiss) { Text("Batal") }
                    Button(
                        onClick = { onConfirm(selectedDays) },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCoral)
                    ) { Text("Hapus hasil lama") }
                }
            }
        }
    }
}

@Composable
private fun HistoryHeader(
    total: Int,
    totalBytes: Long,
    onSelectFiles: () -> Unit,
    onCleanupOld: () -> Unit
) {
    Column(modifier = Modifier.padding(bottom = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Riwayat",
                style = MaterialTheme.typography.headlineLarge,
                color = DeepNavyDisplay,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = PastelPeriwinkleCardBg
            ) {
                Text(
                    text = "$total tersimpan · ${formatFileSize(totalBytes)}",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentRoyalBlue
                )
            }
            IconButton(onClick = onCleanupOld, modifier = Modifier.size(42.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "Hapus hasil lama", tint = SubtitleSlate)
            }
            IconButton(onClick = onSelectFiles, modifier = Modifier.size(42.dp)) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Pilih file", tint = AccentRoyalBlue)
            }
        }
        Spacer(Modifier.size(4.dp))
        Text(
            "Putar kembali, kirim ulang, atau rapikan voice note yang tersimpan.",
            style = MaterialTheme.typography.bodyMedium,
            color = SubtitleSlate
        )
    }
}

@Composable
private fun SelectionHeader(selectedCount: Int, onCancel: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Default.Close, contentDescription = "Batal memilih", tint = SubtitleSlate)
        }
        Text(
            "$selectedCount dipilih",
            style = MaterialTheme.typography.headlineSmall,
            color = DeepNavyDisplay,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .weight(1f)
                .testTag("history_selection_count")
        )
        Text(
            "Ketuk file untuk memilih",
            style = MaterialTheme.typography.labelMedium,
            color = SubtitleSlate
        )
    }
}

@Composable
private fun HistorySectionHeader(
    title: String,
    count: Int?,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = SubtitleSlate,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onToggle)
                .padding(vertical = 4.dp)
        )
        count?.let {
            Text(
                text = it.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = SubtitleSlate
            )
        }
        IconButton(
            onClick = onToggle,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = if (expanded) "Lipat $title" else "Buka $title",
                tint = SubtitleSlate
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryItemCard(
    item: ConversionHistory,
    sectionTitle: String,
    isCurrent: Boolean,
    playbackStateFlow: StateFlow<PlaybackState>,
    selectionMode: Boolean,
    selected: Boolean,
    onPlayPause: () -> Unit,
    onShare: () -> Unit,
    onConfirmSent: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleSelection: () -> Unit
) {
    var showActions by remember(item.id) { mutableStateOf(false) }
    val waveform = remember(isCurrent, item.waveform) {
        if (isCurrent) parseWaveform(item.waveform) else emptyList()
    }
    val currentOnPlayPause = rememberUpdatedState(onPlayPause)
    val currentOnToggleSelection = rememberUpdatedState(onToggleSelection)
    val metadata = remember(item.createdAt, item.durationSeconds, item.fileSizeBytes, sectionTitle) {
        formatHistoryMetadata(item, sectionTitle)
    }
    val playbackState = if (isCurrent) {
        val activePlaybackState by playbackStateFlow.collectAsStateWithLifecycle()
        activePlaybackState
    } else {
        PlaybackState()
    }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart && !selectionMode) onDelete()
            false
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = !selectionMode,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(18.dp))
                    .background(PastelPeachCardBg)
                    .padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(Icons.Default.Delete, "Hapus", tint = AccentCoral)
            }
        }
    ) {
        val rowActionLabel = if (selectionMode) {
            if (selected) "Batalkan pilihan" else "Pilih"
        } else {
            "Putar"
        }
        val rowContentDescription = if (selectionMode) {
            "Voice note ${item.originalFileName}"
        } else {
            "Putar ${item.originalFileName}"
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("history_item_${item.id}")
                .pointerInput(item.id, selectionMode) {
                    detectTapGestures(
                        onTap = {
                            if (selectionMode) {
                                currentOnToggleSelection.value()
                            } else {
                                currentOnPlayPause.value()
                            }
                        },
                        onLongPress = { currentOnToggleSelection.value() }
                    )
                }
                .semantics {
                    contentDescription = rowContentDescription
                    onClick(label = rowActionLabel) {
                        if (selectionMode) currentOnToggleSelection.value() else currentOnPlayPause.value()
                        true
                    }
                },
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurfaceWhite),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (selectionMode) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = { currentOnToggleSelection.value() },
                                modifier = Modifier.semantics {
                                    contentDescription = if (selected) {
                                        "Batalkan pilihan ${item.originalFileName}"
                                    } else {
                                        "Pilih ${item.originalFileName}"
                                    }
                                }
                            )
                        } else {
                            IconButton(
                                onClick = onPlayPause,
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(PastelPeriwinkleCardBg, CircleShape)
                            ) {
                                Icon(
                                    if (isCurrent && playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isCurrent && playbackState.isPlaying) "Pause" else "Putar",
                                    tint = AccentRoyalBlue
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.originalFileName,
                                style = MaterialTheme.typography.titleMedium,
                                color = DeepNavyDisplay,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                item.outputFileName,
                                style = MaterialTheme.typography.bodySmall,
                                color = SubtitleSlate,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        HistoryStatusBadge(status = item.deliveryStatus)
                        if (!selectionMode) {
                            IconButton(
                                onClick = { showActions = !showActions },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(Icons.Default.MoreVert, "Opsi lainnya", tint = SubtitleSlate)
                            }
                        }
                    }

                    Text(
                        metadata,
                        style = MaterialTheme.typography.labelSmall,
                        color = SubtitleSlate,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    if (isCurrent) {
                        WaveformVisualizer(
                            waveform = waveform,
                            progress = playbackState.progress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            activeColor = AccentRoyalBlue,
                            inactiveColor = SubtleBorder,
                            playheadColor = AccentCoral,
                            height = 30.dp,
                            showPlayhead = true,
                            isInteractive = false
                        )
                        playbackState.errorMessage?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }

                    if (showActions && !selectionMode) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                                .testTag("history_inline_actions"),
                            shape = RoundedCornerShape(14.dp),
                            color = PastelPeriwinkleCardBg
                        ) {
                            Row(
                                modifier = Modifier.padding(6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = {
                                        showActions = false
                                        onShare()
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CardSurfaceWhite,
                                        contentColor = AccentRoyalBlue
                                    )
                                ) { Text("Kirim ulang") }
                                Button(
                                    onClick = {
                                        showActions = false
                                        onEdit()
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CardSurfaceWhite,
                                        contentColor = AccentRoyalBlue
                                    )
                                ) { Text("Edit") }
                                if (item.deliveryStatus == DeliveryStatus.SHARE_OPENED) {
                                    Button(
                                        onClick = {
                                            showActions = false
                                            onConfirmSent()
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = PastelMintCardBg,
                                            contentColor = PastelMintText
                                        )
                                    ) { Text("Tandai terkirim") }
                                }
                                Button(
                                    onClick = {
                                        showActions = false
                                        onDelete()
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PastelPeachCardBg,
                                        contentColor = AccentCoral
                                    )
                                ) { Text("Hapus") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.BulkSelectionBar(
    selectedCount: Int,
    bottomPadding: Dp,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = bottomPadding + 8.dp),
        shape = RoundedCornerShape(18.dp),
        color = DeepNavyDisplay,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
            Text(
                "$selectedCount dipilih",
                modifier = Modifier.weight(1f),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge
            )
            TextButton(onClick = onDelete) {
                Text("Hapus terpilih", color = PastelPeachCardBg)
            }
        }
    }
}

@Composable
private fun HistoryStatusBadge(status: DeliveryStatus) {
    val confirmed = status == DeliveryStatus.CONFIRMED_SENT
    val opened = status == DeliveryStatus.SHARE_OPENED
    Surface(
        shape = RoundedCornerShape(50),
        color = if (confirmed) PastelMintCardBg else PastelPeriwinkleCardBg
    ) {
        Text(
            text = when {
                confirmed -> "Dikonfirmasi terkirim"
                opened -> "Dibuka di Telegram"
                else -> "Siap dikirim"
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (confirmed) PastelMintText else AccentRoyalBlue,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EmptyHistoryState(
    modifier: Modifier = Modifier,
    onStartConversion: () -> Unit = {}
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(92.dp)
                .background(PastelMintCardBg, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.History,
                contentDescription = null,
                modifier = Modifier.size(46.dp),
                tint = PastelMintText
            )
        }
        Spacer(Modifier.size(18.dp))
        Text(
            "Belum ada konversi",
            style = MaterialTheme.typography.headlineMedium,
            color = DeepNavyDisplay
        )
        Spacer(Modifier.size(6.dp))
        Text(
            "Buat voice note pertama dari audio atau video.",
            style = MaterialTheme.typography.bodyMedium,
            color = SubtitleSlate
        )
        Spacer(Modifier.size(20.dp))
        Button(onClick = onStartConversion) {
            Icon(Icons.Default.Mic, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Mulai konversi")
        }
    }
}

@Composable
private fun NoHistoryResultsState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Tidak ada hasil",
            style = MaterialTheme.typography.titleLarge,
            color = DeepNavyDisplay
        )
        Spacer(Modifier.size(6.dp))
        Text(
            "Coba kata kunci atau filter lain.",
            style = MaterialTheme.typography.bodyMedium,
            color = SubtitleSlate
        )
    }
}

private fun parseWaveform(value: String): List<Int> = WaveformCodec.decode(value)

private fun formatHistoryMetadata(item: ConversionHistory, sectionTitle: String): String {
    val datePattern = when (sectionTitle) {
        "Hari ini" -> "'Hari ini,' HH:mm"
        "Kemarin" -> "'Kemarin,' HH:mm"
        else -> "dd MMM yyyy, HH:mm"
    }
    val date = SimpleDateFormat(datePattern, Locale.getDefault()).format(Date(item.createdAt))
    val duration = "%02d:%02d".format(item.durationSeconds / 60, item.durationSeconds % 60)
    return "$date · $duration · ${formatFileSize(item.fileSizeBytes)}"
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024 * 1024) {
        return if (bytes % 1024L == 0L) {
            "${bytes / 1024L} KB"
        } else {
            "%.1f KB".format(bytes / 1024f)
        }
    }
    return if (bytes % (1024L * 1024L) == 0L) {
        "${bytes / (1024L * 1024L)} MB"
    } else {
        "%.1f MB".format(bytes / (1024f * 1024f))
    }
}

private fun historyFilterLabel(filter: HistoryFilter): String = when (filter) {
    HistoryFilter.ALL -> "Semua"
    HistoryFilter.CONFIRMED -> "Dikonfirmasi"
    HistoryFilter.NOT_CONFIRMED -> "Belum dikonfirmasi"
}

private fun historySortLabel(sort: HistorySort): String = when (sort) {
    HistorySort.NEWEST -> "Terbaru"
    HistorySort.OLDEST -> "Terlama"
    HistorySort.NAME -> "Nama"
    HistorySort.SIZE -> "Ukuran"
}
