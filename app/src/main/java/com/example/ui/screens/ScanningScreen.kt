package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ocr.OcrResult
import com.example.ocr.OcrWord
import com.example.ui.PageScanState
import com.example.ui.ScanPage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanningScreen(
    pages: List<ScanPage>,
    initialPageIndex: Int,
    onPageFocused: (Int) -> Unit,
    onRetryPage: (Int) -> Unit,
    onDeleteCurrentPage: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(initialPage = initialPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))) {
        pages.size.coerceAtLeast(1)
    }

    LaunchedEffect(pagerState.currentPage) {
        onPageFocused(pagerState.currentPage)
    }

    val currentPage = pages.getOrNull(pagerState.currentPage)
    val pagerScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (pages.size > 1) "Scanning • ${pagerState.currentPage + 1}/${pages.size}" else "Scanning",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("scanning_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (currentPage?.state == PageScanState.DONE) {
                        IconButton(
                            onClick = { showDeleteConfirmDialog = true },
                            modifier = Modifier.testTag("delete_page_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Delete this page",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) { index ->
                val page = pages.getOrNull(index)
                if (page != null) {
                    ScanPageContent(
                        page = page,
                        onRetry = { onRetryPage(index) },
                        onHorizontalSwipe = { direction ->
                            val target = (pagerState.currentPage + direction).coerceIn(0, pages.lastIndex)
                            if (target != pagerState.currentPage) {
                                pagerScope.launch { pagerState.animateScrollToPage(target) }
                            }
                        }
                    )
                }
            }

            if (pages.size > 1) {
                PageThumbnailStrip(
                    pages = pages,
                    currentIndex = pagerState.currentPage,
                    pagerState = pagerState
                )
            }
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete this page?", fontWeight = FontWeight.Bold) },
            text = { Text("This page and its stored image will be removed permanently from your local device.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDeleteCurrentPage()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.testTag("confirm_delete_page_button")
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun PageThumbnailStrip(
    pages: List<ScanPage>,
    currentIndex: Int,
    pagerState: androidx.compose.foundation.pager.PagerState
) {
    val scope = rememberCoroutineScope()
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(pages.size) { idx ->
                val page = pages[idx]
                val selected = idx == currentIndex
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .testTag("page_thumb_$idx"),
                    contentAlignment = Alignment.Center
                ) {
                    if (page.bitmap != null) {
                        Image(
                            bitmap = page.bitmap.asImageBitmap(),
                            contentDescription = "Page ${idx + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }
                    if (page.state == PageScanState.SCANNING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                scope.launch { pagerState.animateScrollToPage(idx) }
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanPageContent(
    page: ScanPage,
    onRetry: () -> Unit,
    onHorizontalSwipe: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    fun copyText(text: String) {
        if (text.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Extracted text", text))
    }

    fun shareText(text: String) {
        if (text.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share extracted text"))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (page.state) {
            PageScanState.SCANNING, PageScanState.PENDING -> {
                ScanningBeamCard(bitmap = page.bitmap, modifier = Modifier.weight(1f).fillMaxWidth())
                StatusBar(message = "Analyzing layout and detecting text…")
            }

            PageScanState.FAILED -> {
                ScanningBeamCard(bitmap = page.bitmap, showBeam = false, modifier = Modifier.weight(1f).fillMaxWidth())
                ErrorCard(message = page.errorMessage ?: "Something went wrong.", onRetry = onRetry)
            }

            PageScanState.DONE -> {
                val result = page.result
                if (result != null && result.hasPositionedWords && page.bitmap != null) {
                    ImageSelectableTextOverlay(
                        bitmap = page.bitmap,
                        words = result.words,
                        onHorizontalSwipe = onHorizontalSwipe,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                } else if (page.bitmap != null) {
                    // No position data (Gemini AI path, or a page with no extractable boxes):
                    // show the image with a plain selectable text block beneath it.
                    Card(
                        modifier = Modifier.weight(0.55f).fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Image(
                            bitmap = page.bitmap.asImageBitmap(),
                            contentDescription = "Scanned page",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(8.dp).clip(RoundedCornerShape(8.dp))
                        )
                    }
                    Surface(
                        modifier = Modifier.weight(0.55f).fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        val scrollState = rememberScrollState()
                        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                            Text(
                                "Extracted text",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(8.dp))
                            if (result?.fullText.isNullOrBlank()) {
                                Text(
                                    "No text was detected in this image.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                SelectionContainer(
                                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState)
                                ) {
                                    Text(
                                        text = result?.fullText.orEmpty(),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { shareText(result?.fullText.orEmpty()) },
                        modifier = Modifier.weight(1f).height(48.dp).testTag("share_page_button"),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share")
                    }
                    Button(
                        onClick = { copyText(result?.fullText.orEmpty()) },
                        modifier = Modifier.weight(1.3f).height(48.dp).testTag("copy_all_page_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Copy All Text")
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanningBeamCard(
    bitmap: android.graphics.Bitmap?,
    showBeam: Boolean = true,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransitionSafe()
    val scanProgress by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scan_beam_pos"
    )

    Card(
        modifier = modifier.testTag("scanning_view_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Scanning Preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                )
            } else {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
            }

            if (showBeam) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val beamY = size.height * scanProgress
                    val glowHeight = 36.dp.toPx()
                    val beamHeight = 4.dp.toPx()

                    val glowBrush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.14f), Color.Transparent),
                        startY = beamY - glowHeight / 2,
                        endY = beamY + glowHeight / 2
                    )
                    drawRect(
                        brush = glowBrush,
                        topLeft = Offset(0f, beamY - glowHeight / 2),
                        size = Size(size.width, glowHeight)
                    )

                    val beamBrush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.2f),
                            Color.White,
                            Color.White,
                            Color.White,
                            Color.White.copy(alpha = 0.2f)
                        )
                    )
                    drawRect(
                        brush = beamBrush,
                        topLeft = Offset(0f, beamY - beamHeight / 2),
                        size = Size(size.width, beamHeight)
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberInfiniteTransitionSafe() =
    androidx.compose.animation.core.rememberInfiniteTransition(label = "scan_beam_transition")

@Composable
private fun StatusBar(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    strokeWidth = 2.5.dp
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Processing 100% on-device • Instant & private",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                Text("Scan Failed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("scanning_retry_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Retry Scan")
            }
        }
    }
}

// ---------------------------------------------------------------------
// Live Text-style selectable overlay: renders the image, then an invisible
// text layer positioned per-word on top of it, so dragging over the image
// selects the underlying recognized words (like iOS Live Text).
// ---------------------------------------------------------------------

@Composable
private fun ImageSelectableTextOverlay(
    bitmap: android.graphics.Bitmap,
    words: List<OcrWord>,
    onHorizontalSwipe: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedIndices by remember(words) { mutableStateOf(setOf<Int>()) }
    var dragAnchorIndex by remember(words) { mutableStateOf<Int?>(null) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var pageSwipeHandled by remember { mutableStateOf(false) }

    fun wordsRectsInBox(boxWidthPx: Float, boxHeightPx: Float): List<androidx.compose.ui.geometry.Rect> {
        val bmpW = bitmap.width.toFloat()
        val bmpH = bitmap.height.toFloat()
        if (bmpW <= 0f || bmpH <= 0f || boxWidthPx <= 0f || boxHeightPx <= 0f) return emptyList()

        // Replicates ContentScale.Fit letterboxing math so overlay boxes line up with the
        // displayed image regardless of the container's aspect ratio.
        val scale = minOf(boxWidthPx / bmpW, boxHeightPx / bmpH)
        val drawnWidth = bmpW * scale
        val drawnHeight = bmpH * scale
        val offsetX = (boxWidthPx - drawnWidth) / 2f
        val offsetY = (boxHeightPx - drawnHeight) / 2f

        return words.map { word ->
            androidx.compose.ui.geometry.Rect(
                left = offsetX + word.boundingBox.left * scale,
                top = offsetY + word.boundingBox.top * scale,
                right = offsetX + word.boundingBox.right * scale,
                bottom = offsetY + word.boundingBox.bottom * scale
            )
        }
    }

    fun copySelection() {
        if (selectedIndices.isEmpty()) return
        val text = selectedIndices.sorted().joinToString(" ") { words[it].text }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Selected text", text))
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("overlay_image_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                val boxWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }
                val boxHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxHeight.toPx() }
                val rects = remember(words, boxWidthPx, boxHeightPx) { wordsRectsInBox(boxWidthPx, boxHeightPx) }

                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Scanned page",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                )

                // Selection highlight + hit-testing layer.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(rects) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    dragStart = offset
                                    pageSwipeHandled = false
                                    val idx = rects.indexOfFirst { it.contains(offset) }
                                    dragAnchorIndex = if (idx >= 0) idx else null
                                    selectedIndices = if (idx >= 0) setOf(idx) else emptySet()
                                },
                                onDrag = { change, _ ->
                                    val start = dragStart
                                    if (start != null && !pageSwipeHandled &&
                                        kotlin.math.abs(change.position.x - start.x) > 64.dp.toPx() &&
                                        kotlin.math.abs(change.position.x - start.x) > kotlin.math.abs(change.position.y - start.y)
                                    ) {
                                        pageSwipeHandled = true
                                        dragAnchorIndex = null
                                        onHorizontalSwipe(if (change.position.x < start.x) 1 else -1)
                                    }
                                    if (pageSwipeHandled) return@detectDragGestures
                                    val idx = rects.indexOfFirst { it.contains(change.position) }
                                    val anchor = dragAnchorIndex
                                    if (idx >= 0 && anchor != null) {
                                        val range = if (idx >= anchor) anchor..idx else idx..anchor
                                        selectedIndices = range.toSet()
                                    }
                                },
                                onDragEnd = {
                                    dragStart = null
                                    pageSwipeHandled = false
                                    /* selection stays until cleared */
                                }
                            )
                        }
                ) {
                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                        rects.forEachIndexed { idx, rect ->
                            if (idx in selectedIndices) {
                                drawRect(
                                    color = Color(0xFF9A9A9A).copy(alpha = 0.35f),
                                    topLeft = Offset(rect.left, rect.top),
                                    size = Size(rect.width, rect.height)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (selectedIndices.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${selectedIndices.size} word${if (selectedIndices.size == 1) "" else "s"} selected",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row {
                        TextButton(onClick = { selectedIndices = words.indices.toSet() }) {
                            Icon(Icons.Filled.SelectAll, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Select All")
                        }
                        TextButton(onClick = { copySelection() }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copy")
                        }
                    }
                }
            }
        }
    }
}