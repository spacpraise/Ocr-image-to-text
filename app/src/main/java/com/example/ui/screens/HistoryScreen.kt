package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.ScanEntity
import com.example.ui.theme.DangerRed
import com.example.ui.theme.PrimaryBlue
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    scans: List<ScanEntity>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onReopenScan: (ScanEntity) -> Unit,
    onDeleteScan: (ScanEntity) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var scanToDelete by remember { mutableStateOf<ScanEntity?>(null) }
    var gridMode by remember { mutableStateOf(false) }
    var newestFirst by remember { mutableStateOf(true) }
    val historyEntries = remember(scans, newestFirst) {
        scans.groupBy { scan -> scan.batchId ?: "single:${scan.id}" }
            .values
            .map { pages ->
                val orderedPages = pages.sortedBy { it.pageIndex }
                HistoryEntry(
                    key = orderedPages.first().batchId ?: "single:${orderedPages.first().id}",
                    pages = orderedPages,
                    scan = orderedPages.firstOrNull { it.pageIndex == 0 } ?: orderedPages.first(),
                    timestamp = pages.maxOf { it.timestamp }
                )
            }
            .let { entries -> if (newestFirst) entries.sortedByDescending { it.timestamp } else entries.sortedBy { it.timestamp } }
    }
    val dateGroups = remember(historyEntries) {
        historyEntries.groupBy { historyDateLabel(it.timestamp) }
    }

    fun copyText(text: String, title: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("SnapText OCR", text)
        clipboard.setPrimaryClip(clip)
        coroutineScope.launch {
            snackbarHostState.showSnackbar("Copied text from $title")
        }
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(0.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp)
                    .testTag("history_search_input"),
                placeholder = {
                    Text(
                        text = "Search scans by title or text…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { onSearchQueryChange("") },
                            modifier = Modifier.testTag("clear_search_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = "Clear Search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryBlue,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )

            Spacer(modifier = Modifier.height(0.dp))
            Row(
                modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { newestFirst = !newestFirst }, modifier = Modifier.size(36.dp).testTag("history_sort_order")) {
                    Icon(Icons.Filled.SwapVert, contentDescription = if (newestFirst) "Newest first; switch to oldest first" else "Oldest first; switch to newest first")
                }
                IconButton(onClick = { gridMode = false }, modifier = Modifier.size(36.dp).testTag("history_list_view")) {
                    Icon(Icons.Filled.ViewList, contentDescription = "List view", tint = if (!gridMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { gridMode = true }, modifier = Modifier.size(36.dp).testTag("history_grid_view")) {
                    Icon(Icons.Filled.GridView, contentDescription = "Grid view", tint = if (gridMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Count badge
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${historyEntries.size} ${if (historyEntries.size == 1) "Item" else "Items"}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "Saved locally",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Scans List
            if (historyEntries.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(bottom = 80.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Surface(Modifier.size(56.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp)) }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(if (searchQuery.isNotEmpty()) "No matching scans" else "No scan history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(if (searchQuery.isNotEmpty()) "Try another word or clear the search." else "Images and documents you scan will appear here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                    }
                }
            } else if (gridMode) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 148.dp),
                    modifier = Modifier.fillMaxSize().widthIn(max = 680.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 100.dp)
                ) {
                    gridItems(historyEntries, key = { it.key }) { entry ->
                        HistoryGridCard(entry.scan, entry.pages.size, entry.timestamp) { onReopenScan(entry.scan) }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().widthIn(max = 680.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 100.dp)
                ) {
                    dateGroups.forEach { (dateLabel, entries) ->
                        item(key = "date_$dateLabel") {
                            Text(dateLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                        }
                        items(entries, key = { it.key }) { entry ->
                            val scan = entry.scan
                            val combinedText = entry.pages.joinToString("\n\n") { it.extractedText }.trim()
                            HistoryScanCard(scan = scan, pageCount = entry.pages.size, timestamp = entry.timestamp, onReopen = { onReopenScan(scan) }, onCopy = { copyText(combinedText, scan.title) }, onDelete = { scanToDelete = scan })
                        }
                    }
                }
            }
        }
    }

    // Delete single scan confirmation
    scanToDelete?.let { scan ->
        AlertDialog(
            onDismissRequest = { scanToDelete = null },
            title = {
                Text(
                    text = "Delete scan?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete \"${scan.title}\" and all pages in this document or batch? This action cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteScan(scan)
                        scanToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                    modifier = Modifier.testTag("confirm_delete_history_item")
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { scanToDelete = null },
                    modifier = Modifier.testTag("dismiss_delete_history_item")
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

private data class HistoryEntry(
    val key: String,
    val pages: List<ScanEntity>,
    val scan: ScanEntity,
    val timestamp: Long
)

private fun historyDateLabel(timestamp: Long): String {
    val now = java.util.Calendar.getInstance()
    val target = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    fun dayStart(calendar: java.util.Calendar): Long = (calendar.clone() as java.util.Calendar).apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val dayDiff = ((dayStart(now) - dayStart(target)) / 86_400_000L).toInt()
    return when (dayDiff) {
        0 -> "Today"
        1 -> "Yesterday"
        else -> SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}

@Composable
private fun HistoryGridCard(scan: ScanEntity, pageCount: Int, timestamp: Long, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(9.dp)) {
            val thumbFile = (scan.thumbnailPath ?: scan.imagePath)?.let { File(it) }
            Surface(Modifier.fillMaxWidth().height(100.dp), shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                if (thumbFile != null && thumbFile.exists()) AsyncImage(model = thumbFile, contentDescription = "Scan thumbnail", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Description, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(32.dp)) }
            }
            Spacer(Modifier.height(8.dp))
            Text(if (pageCount > 1) "${scan.title} · ${pageCount} pages" else scan.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestamp)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun HistoryScanCard(
    scan: ScanEntity,
    pageCount: Int,
    timestamp: Long,
    onReopen: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onReopen)
            .testTag("history_item_${scan.id}"),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 7.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail
            Surface(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(7.dp)),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                val thumbFile = (scan.thumbnailPath ?: scan.imagePath)?.let { File(it) }
                if (thumbFile != null && thumbFile.exists()) {
                    AsyncImage(
                        model = thumbFile,
                        contentDescription = "Scan Thumbnail",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Description,
                            contentDescription = null,
                            tint = PrimaryBlue,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(7.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (pageCount > 1) "${scan.title} · ${pageCount} pages" else scan.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = scan.extractedText.ifBlank { "No text recognized" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(3.dp))

                val formattedDate = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
                    .format(Date(timestamp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = formattedDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = "${scan.characterCount} chars",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = PrimaryBlue
                    )
                }
            }

            // Quick Copy and Delete Actions
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(34.dp).testTag("history_copy_${scan.id}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "Copy text",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(34.dp).testTag("history_delete_${scan.id}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = "Delete scan",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
