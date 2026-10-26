package com.example.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.AppThemeMode
import com.example.data.PreferencesManager
import com.example.data.ScanEntity
import com.example.ocr.ImageMetadata
import com.example.ocr.ImageUtils
import com.example.ocr.OcrEngine
import com.example.ocr.OcrResult
import com.example.ocr.SampleDocumentType
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

sealed interface Screen {
    object Onboarding : Screen
    object Home : Screen
    object History : Screen
    object Settings : Screen
    object Account : Screen
    object ImagePreview : Screen
    object Scanning : Screen
}

/** The per-page scan lifecycle state, driving what ScanningScreen shows for that page. */
enum class PageScanState {
    PENDING,   // not scanned yet (lazy pages the user hasn't swiped to)
    SCANNING,  // actively running OCR, show the animated beam
    DONE,      // OcrResult available
    FAILED     // OCR threw; errorMessage is set
}

/** One page within the current scanning batch (a single photo is a 1-page batch). */
data class ScanPage(
    val bitmap: Bitmap?,
    val state: PageScanState = PageScanState.PENDING,
    val result: OcrResult? = null,
    val errorMessage: String? = null,
    val savedScanId: Long? = null,
    // Only set for PDF-backed pages; lets scanPageIfNeeded re-render/re-extract lazily.
    val pdfPageIndex: Int? = null
)

/** Free-tier daily scan allowance. Premium accounts are unlimited and skip this check entirely. */
private const val FREE_DAILY_SCAN_LIMIT = 3

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val scanDao = db.scanDao()
    private val prefs = PreferencesManager(application)

    private val _currentScreen = MutableStateFlow<Screen>(
        if (prefs.isOnboardingCompleted) Screen.Home else Screen.Onboarding
    )
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    private val screenStack = mutableListOf<Screen>()

    private val _themeMode = MutableStateFlow(prefs.themeMode)
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    // Single-image preview state (used only for the ImagePreview screen: camera / single
    // gallery pick / sample doc, before the user commits to scanning).
    private val _selectedBitmap = MutableStateFlow<Bitmap?>(null)
    val selectedBitmap: StateFlow<Bitmap?> = _selectedBitmap.asStateFlow()

    private val _imageMetadata = MutableStateFlow<ImageMetadata?>(null)
    val imageMetadata: StateFlow<ImageMetadata?> = _imageMetadata.asStateFlow()

    // --- Batch / pager state (drives ScanningScreen) ---
    private val _pages = MutableStateFlow<List<ScanPage>>(emptyList())
    val pages: StateFlow<List<ScanPage>> = _pages.asStateFlow()

    private val _currentPageIndex = MutableStateFlow(0)
    val currentPageIndex: StateFlow<Int> = _currentPageIndex.asStateFlow()

    private var currentBatchId: String? = null
    private var stagedPdfFile: File? = null

    private val _docImportError = MutableStateFlow<String?>(null)
    val docImportError: StateFlow<String?> = _docImportError.asStateFlow()

    // --- Free-tier daily usage (resets at local midnight) ---
    private val _scansUsedToday = MutableStateFlow(prefs.scansUsedForToday())
    val scansRemainingToday: StateFlow<Int> =
        _scansUsedToday.map { (FREE_DAILY_SCAN_LIMIT - it).coerceAtLeast(0) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FREE_DAILY_SCAN_LIMIT)

    private val _dailyLimitReached = MutableStateFlow<Boolean?>(null)
    /** Set to true right when a free-tier scan attempt is blocked; UI shows the upgrade prompt then clears it. */
    val dailyLimitReached: StateFlow<Boolean?> = _dailyLimitReached.asStateFlow()

    fun clearDailyLimitSignal() { _dailyLimitReached.value = null }

    // Search and Scans History
    val allScans: StateFlow<List<ScanEntity>> = scanDao.getAllScans()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Home screen "recent" list: the last 3 history *entries* (one card per batch --
    // a whole PDF or a whole multi-image pick counts as one), not the last 3 raw rows.
    // A 5-page PDF scan must not fill this list by itself.
    val recentScans: StateFlow<List<ScanEntity>> = allScans
        .map { scans ->
            scans.groupBy { it.batchId ?: "single:${it.id}" }
                .values
                .map { pages -> pages.maxByOrNull { it.timestamp } ?: pages.first() }
                .sortedByDescending { it.timestamp }
                .take(3)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val filteredScans: StateFlow<List<ScanEntity>> = combine(allScans, _searchQuery) { scans, query ->
        if (query.isBlank()) scans
        else scans.filter {
            it.title.contains(query, ignoreCase = true) || it.extractedText.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _storageInfo = MutableStateFlow("Calculating...")
    val storageInfo: StateFlow<String> = _storageInfo.asStateFlow()

    init {
        updateStorageInfo()
        PDFBoxResourceLoader.init(application.applicationContext)
    }

    fun completeOnboarding() {
        prefs.isOnboardingCompleted = true
        navigateTo(Screen.Home, clearStack = true)
    }

    fun setThemeMode(mode: AppThemeMode) {
        prefs.themeMode = mode
        _themeMode.value = mode
    }

    fun navigateTo(screen: Screen, clearStack: Boolean = false) {
        if (clearStack) {
            screenStack.clear()
        } else if (_currentScreen.value != screen) {
            screenStack.add(_currentScreen.value)
        }
        _currentScreen.value = screen
    }

    fun handleBack(): Boolean {
        if (screenStack.isNotEmpty()) {
            val previous = screenStack.removeAt(screenStack.size - 1)
            _currentScreen.value = previous
            return true
        } else if (_currentScreen.value != Screen.Home && prefs.isOnboardingCompleted) {
            _currentScreen.value = Screen.Home
            return true
        }
        return false
    }

    // ---------------------------------------------------------------------
    // Single image entry points (camera photo, single gallery pick, share-in,
    // sample document) -- these go through ImagePreview first, same as before.
    // ---------------------------------------------------------------------

    fun onImagePicked(uri: Uri, context: Context, autoScan: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val bitmap = ImageUtils.loadBitmapFromUri(context, uri)
            val metadata = if (bitmap != null) ImageUtils.getImageMetadata(context, uri) else null
            withContext(Dispatchers.Main) {
                _selectedBitmap.value = bitmap
                _imageMetadata.value = metadata

                if (bitmap == null) {
                    beginSinglePageBatchWithError(
                        "This image could not be opened. It may be unsupported, unavailable, or too large. Try saving it to your device and sharing it again."
                    )
                } else if (autoScan) {
                    beginBatch(listOf(bitmap), context, pdfPageIndices = null)
                } else {
                    navigateTo(Screen.ImagePreview)
                }
            }
        }
    }

    fun onSamplePicked(type: SampleDocumentType, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val bitmap = ImageUtils.createSampleDocumentBitmap(type)
            val metadata = ImageMetadata(
                filename = "${type.title.lowercase().replace(" ", "_")}.png",
                dimensions = "${bitmap.width} × ${bitmap.height}",
                formattedSize = "320 KB",
                format = "PNG"
            )
            withContext(Dispatchers.Main) {
                _selectedBitmap.value = bitmap
                _imageMetadata.value = metadata
                navigateTo(Screen.ImagePreview)
            }
        }
    }

    fun rotateSelectedImage() {
        val current = _selectedBitmap.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val rotated = ImageUtils.rotateBitmap(current, 90f)
            val meta = _imageMetadata.value
            val newDimensions = "${rotated.width} × ${rotated.height}"
            withContext(Dispatchers.Main) {
                _selectedBitmap.value = rotated
                _imageMetadata.value = meta?.copy(dimensions = newDimensions)
            }
        }
    }

    fun cropSelectedImage() {
        val current = _selectedBitmap.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val cropped = ImageUtils.cropCenterBitmap(current, 0.85f)
            val meta = _imageMetadata.value
            val newDimensions = "${cropped.width} × ${cropped.height}"
            withContext(Dispatchers.Main) {
                _selectedBitmap.value = cropped
                _imageMetadata.value = meta?.copy(dimensions = newDimensions)
            }
        }
    }

    /** Called from ImagePreviewScreen's "Scan for Text" button for the single-image flow. */
    fun startScanning(context: Context) {
        val bitmap = _selectedBitmap.value ?: return
        beginBatch(listOf(bitmap), context, pdfPageIndices = null)
    }

    // ---------------------------------------------------------------------
    // Multi-image gallery pick
    // ---------------------------------------------------------------------

    fun onImagesPicked(uris: List<Uri>, context: Context) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = ImageUtils.loadBitmapsFromUris(context, uris)
            withContext(Dispatchers.Main) {
                if (loaded.isEmpty()) {
                    beginSinglePageBatchWithError(
                        "None of the selected images could be opened. Try selecting them again."
                    )
                    return@withContext
                }
                beginBatch(loaded.map { it.second }, context, pdfPageIndices = null)
            }
        }
    }

    // ---------------------------------------------------------------------
    // PDF document import
    // ---------------------------------------------------------------------

    fun onPdfPicked(uri: Uri, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val staged = ImageUtils.stagePdfToCache(context, uri)
            if (staged == null) {
                withContext(Dispatchers.Main) {
                    beginSinglePageBatchWithError("This document could not be opened. Please try again.")
                }
                return@launch
            }
            val pageCount = ImageUtils.getPdfPageCount(staged)
            if (pageCount == null || pageCount <= 0) {
                withContext(Dispatchers.Main) {
                    beginSinglePageBatchWithError("This PDF has no readable pages.")
                }
                return@launch
            }

            stagedPdfFile = staged
            // Render small thumbnails up front so every PDF page is visible in the
            // bottom strip immediately, without requiring the user to open each page.
            val placeholderPages = (0 until pageCount).map { index ->
                val thumbnail = ImageUtils.renderPdfPage(staged, index, targetMaxDimension = 180)?.first
                ScanPage(bitmap = thumbnail, state = PageScanState.PENDING, pdfPageIndex = index)
            }

            withContext(Dispatchers.Main) {
                currentBatchId = UUID.randomUUID().toString()
                _pages.value = placeholderPages
                _currentPageIndex.value = 0
                _docImportError.value = null
                navigateTo(Screen.Scanning, clearStack = true)
            }
            scanPageIfNeeded(0, context)
        }
    }

    /** DOCX (and any other unsupported document type) picked -- show a clear message, no crash. */
    fun onUnsupportedDocumentPicked() {
        _docImportError.value = "Word documents (.docx) aren't supported yet. Please share a PDF or an image instead."
    }

    fun clearDocImportError() {
        _docImportError.value = null
    }

    // ---------------------------------------------------------------------
    // Batch orchestration shared by all entry points
    // ---------------------------------------------------------------------

    private fun beginBatch(bitmaps: List<Bitmap>, context: Context, pdfPageIndices: List<Int>?) {
        currentBatchId = if (bitmaps.size > 1) UUID.randomUUID().toString() else null
        stagedPdfFile = null
        _pages.value = bitmaps.map { bmp -> ScanPage(bitmap = bmp, state = PageScanState.PENDING) }
        _currentPageIndex.value = 0
        navigateTo(Screen.Scanning, clearStack = true)
        scanPageIfNeeded(0, context)
    }

    private fun beginSinglePageBatchWithError(message: String) {
        currentBatchId = null
        stagedPdfFile = null
        _pages.value = listOf(ScanPage(bitmap = null, state = PageScanState.FAILED, errorMessage = message))
        _currentPageIndex.value = 0
        navigateTo(Screen.Scanning, clearStack = true)
    }

    /** Called by the UI (pager settle) and internally to lazily scan a page on first visit. */
    fun onPageFocused(index: Int, context: Context) {
        _currentPageIndex.value = index
        scanPageIfNeeded(index, context)
    }

    fun retryPage(index: Int, context: Context) {
        val current = _pages.value.getOrNull(index) ?: return
        updatePage(index) { it.copy(state = PageScanState.PENDING, errorMessage = null) }
        scanPageIfNeeded(index, context, force = true)
    }

    /**
     * Checks the free-tier daily scan allowance before starting a new page scan.
     * Premium accounts (isPremiumProvider returning true) always pass. Reopening an
     * already-scanned page (force retry) does not consume a new allowance slot.
     */
    var isPremiumProvider: () -> Boolean = { false }

    private fun consumeDailyScanAllowanceIfNeeded(): Boolean {
        if (isPremiumProvider()) return true
        val usedToday = prefs.scansUsedForToday()
        if (usedToday >= FREE_DAILY_SCAN_LIMIT) {
            _dailyLimitReached.value = true
            return false
        }
        prefs.incrementScansUsedForToday()
        _scansUsedToday.value = prefs.scansUsedForToday()
        return true
    }

    private fun scanPageIfNeeded(index: Int, context: Context, force: Boolean = false) {
        val page = _pages.value.getOrNull(index) ?: return
        if (!force && page.state != PageScanState.PENDING) return

        if (!force && !consumeDailyScanAllowanceIfNeeded()) {
            updatePage(index) {
                it.copy(
                    state = PageScanState.FAILED,
                    errorMessage = "You've used today's $FREE_DAILY_SCAN_LIMIT free scans. Upgrade to Premium for unlimited scans."
                )
            }
            return
        }

        updatePage(index) { it.copy(state = PageScanState.SCANNING) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                delay(500) // subtle scan line animation feel

                val pdfIndex = page.pdfPageIndex
                val pdfFile = stagedPdfFile

                val (resultBitmap, rawResult) = if (pdfIndex != null && pdfFile != null) {
                    scanPdfPage(pdfFile, pdfIndex)
                } else {
                    val bmp = page.bitmap ?: throw IllegalStateException("No image for this page")
                    bmp to OcrEngine.recognizeTextWithAi(bmp)
                }

                // Second pass: compare the original image with raw OCR and reconstruct
                // headings, paragraphs, lists, and tables before showing/saving the result.
                // If AI polishing is unavailable, OcrEngine returns the raw result unchanged.
                val result = if (resultBitmap != null && rawResult.fullText.isNotBlank()) {
                    OcrEngine.polishExtractedTextWithAi(resultBitmap, rawResult)
                } else {
                    rawResult
                }

                // Persist to history as its own ScanEntity.
                var savedPath: String? = null
                var thumbPath: String? = null
                try {
                    if (resultBitmap != null) {
                        savedPath = ImageUtils.saveBitmapToInternalStorage(context, resultBitmap)
                        thumbPath = ImageUtils.createAndSaveThumbnail(context, resultBitmap)
                    }
                } catch (storageEx: Exception) {
                    Log.e("MainViewModel", "Local image persistence failed: ${storageEx.message}")
                }

                val defaultTitle = if (result.fullText.isNotBlank()) {
                    val firstLine = result.fullText.lines().firstOrNull { it.isNotBlank() } ?: "Scan"
                    if (firstLine.length > 32) firstLine.take(32) + "…" else firstLine
                } else {
                    "Scan ${SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())}"
                }

                val entity = ScanEntity(
                    title = defaultTitle,
                    extractedText = result.fullText,
                    imagePath = savedPath,
                    thumbnailPath = thumbPath,
                    originalFilename = "page_${index + 1}.jpg",
                    timestamp = System.currentTimeMillis(),
                    characterCount = result.characterCount,
                    wordCount = result.wordCount,
                    lineCount = result.lineCount,
                    fileSizeFormatted = "",
                    imageDimensions = resultBitmap?.let { "${it.width} × ${it.height}" } ?: "",
                    confidenceNote = result.confidenceNote,
                    languageDetected = result.languageDetected,
                    batchId = currentBatchId,
                    pageIndex = index,
                    batchPageCount = _pages.value.size
                )

                val savedId = try {
                    scanDao.insertScan(entity)
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Database save error: ${e.message}")
                    null
                }

                withContext(Dispatchers.Main) {
                    updatePage(index) {
                        it.copy(
                            bitmap = resultBitmap ?: it.bitmap,
                            state = PageScanState.DONE,
                            result = result,
                            savedScanId = savedId
                        )
                    }
                    updateStorageInfo()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updatePage(index) {
                        it.copy(
                            state = PageScanState.FAILED,
                            errorMessage = e.localizedMessage ?: "Failed to read text. Please check image quality."
                        )
                    }
                }
            }
        }
    }

    private suspend fun scanPdfPage(pdfFile: File, pdfPageIndex: Int): Pair<Bitmap?, OcrResult> {
        val pageSize = ImageUtils.getPdfPageSize(pdfFile, pdfPageIndex)
        // Render once up front at display resolution; recognizePdfPage only re-invokes this
        // via the lazy callback if native text extraction finds nothing (scanned page), but
        // since we need a bitmap to show on screen regardless, render eagerly here.
        val rendered = ImageUtils.renderPdfPage(pdfFile, pdfPageIndex)
        val bitmap = rendered?.first
        val scale = rendered?.second ?: 1f

        var document: PDDocument? = null
        return try {
            document = PDDocument.load(pdfFile)
            val result = OcrEngine.recognizePdfPage(
                document = document,
                pageIndex = pdfPageIndex,
                pageWidthPt = pageSize?.widthPt ?: 0f,
                pageHeightPt = pageSize?.heightPt ?: 0f,
                renderScale = scale,
                renderPageAsBitmap = { bitmap }
            )
            bitmap to result
        } finally {
            document?.close()
        }
    }

    private fun updatePage(index: Int, transform: (ScanPage) -> ScanPage) {
        val current = _pages.value
        if (index !in current.indices) return
        _pages.value = current.toMutableList().also { it[index] = transform(it[index]) }
    }

    // ---------------------------------------------------------------------
    // Actions on the current page (ScanningScreen toolbar)
    // ---------------------------------------------------------------------
    fun deleteCurrentPageScan(context: Context) {
        val index = _currentPageIndex.value
        val page = _pages.value.getOrNull(index) ?: return
        val scanId = page.savedScanId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val scan = scanDao.getScanById(scanId)
            scan?.let {
                it.imagePath?.let { path -> try { File(path).delete() } catch (_: Exception) {} }
                it.thumbnailPath?.let { path -> try { File(path).delete() } catch (_: Exception) {} }
            }
            scanDao.deleteById(scanId)
            withContext(Dispatchers.Main) {
                updateStorageInfo()
                val remaining = _pages.value.toMutableList().also { it.removeAt(index) }
                if (remaining.isEmpty()) {
                    navigateTo(Screen.Home, clearStack = true)
                } else {
                    _pages.value = remaining
                    _currentPageIndex.value = index.coerceAtMost(remaining.size - 1)
                }
            }
        }
    }

    fun reopenScan(scan: ScanEntity, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val batchScans = scan.batchId?.let { scanDao.getScansByBatchId(it) } ?: listOf(scan)
            val loadedPages = batchScans.map { entry ->
                val bmp = entry.imagePath?.let { ImageUtils.loadBitmapFromFile(it) }
                ScanPage(
                    bitmap = bmp,
                    state = PageScanState.DONE,
                    result = OcrResult(
                        fullText = entry.extractedText,
                        characterCount = entry.characterCount,
                        wordCount = entry.wordCount,
                        lineCount = entry.lineCount,
                        confidenceNote = entry.confidenceNote,
                        languageDetected = entry.languageDetected.ifBlank { "Auto-detected" },
                        isAiModel = false,
                        words = emptyList() // Reopened history has no stored boxes; shows as plain text.
                    ),
                    savedScanId = entry.id
                )
            }
            withContext(Dispatchers.Main) {
                currentBatchId = scan.batchId
                stagedPdfFile = null
                _pages.value = loadedPages
                _currentPageIndex.value = batchScans.indexOfFirst { it.id == scan.id }.coerceAtLeast(0)
                navigateTo(Screen.Scanning)
            }
        }
    }

    fun deleteScan(scan: ScanEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val pages = if (scan.batchId != null) scanDao.getScansByBatchId(scan.batchId) else listOf(scan)
            if (scan.batchId != null) scanDao.deleteByBatchId(scan.batchId) else scanDao.deleteScan(scan)
            pages.forEach { page ->
                page.imagePath?.let { path -> try { File(path).delete() } catch (_: Exception) {} }
                page.thumbnailPath?.let { path -> try { File(path).delete() } catch (_: Exception) {} }
            }
            withContext(Dispatchers.Main) {
                updateStorageInfo()
            }
        }
    }

    fun clearAllScans() {
        viewModelScope.launch(Dispatchers.IO) {
            scanDao.deleteAllScans()
            val scansDir = File(getApplication<Application>().filesDir, "scans")
            if (scansDir.exists()) {
                scansDir.listFiles()?.forEach { it.delete() }
            }
            withContext(Dispatchers.Main) {
                updateStorageInfo()
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private fun updateStorageInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            val count = scanDao.getScanCount()
            val scansDir = File(getApplication<Application>().filesDir, "scans")
            var bytes = 0L
            if (scansDir.exists()) {
                scansDir.listFiles()?.forEach { bytes += it.length() }
            }
            val formatted = if (bytes > 1024 * 1024) {
                String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            } else {
                "${bytes / 1024} KB"
            }
            withContext(Dispatchers.Main) {
                _storageInfo.value = "$count scans saved • $formatted local storage"
            }
        }
    }
}