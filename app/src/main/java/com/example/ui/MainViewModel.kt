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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface Screen {
    object Onboarding : Screen
    object Home : Screen
    object History : Screen
    object Settings : Screen
    object ImagePreview : Screen
    object Scanning : Screen
    object Result : Screen
}

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

    // Active image state
    private val _selectedBitmap = MutableStateFlow<Bitmap?>(null)
    val selectedBitmap: StateFlow<Bitmap?> = _selectedBitmap.asStateFlow()

    private val _selectedUri = MutableStateFlow<Uri?>(null)
    val selectedUri: StateFlow<Uri?> = _selectedUri.asStateFlow()

    private val _imageMetadata = MutableStateFlow<ImageMetadata?>(null)
    val imageMetadata: StateFlow<ImageMetadata?> = _imageMetadata.asStateFlow()

    // Scanning state
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanStatusMessage = MutableStateFlow("Reading text from your image…")
    val scanStatusMessage: StateFlow<String> = _scanStatusMessage.asStateFlow()

    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()

    // OCR Results
    private val _extractedResult = MutableStateFlow<OcrResult?>(null)
    val extractedResult: StateFlow<OcrResult?> = _extractedResult.asStateFlow()

    private val _editableText = MutableStateFlow("")
    val editableText: StateFlow<String> = _editableText.asStateFlow()

    private val _isEditMode = MutableStateFlow(false)
    val isEditMode: StateFlow<Boolean> = _isEditMode.asStateFlow()

    private val _currentSavedScanId = MutableStateFlow<Long?>(null)
    val currentSavedScanId: StateFlow<Long?> = _currentSavedScanId.asStateFlow()

    // Search and Scans History
    val allScans: StateFlow<List<ScanEntity>> = scanDao.getAllScans()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentScans: StateFlow<List<ScanEntity>> = scanDao.getRecentScans(3)
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

    fun onImagePicked(uri: Uri, context: Context, autoScan: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val bitmap = ImageUtils.loadBitmapFromUri(context, uri)
            val metadata = ImageUtils.getImageMetadata(context, uri)
            withContext(Dispatchers.Main) {
                _selectedUri.value = uri
                _selectedBitmap.value = bitmap
                _imageMetadata.value = metadata
                _extractedResult.value = null
                _editableText.value = ""
                _currentSavedScanId.value = null
                if (autoScan) startScanning(context) else navigateTo(Screen.ImagePreview)
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
                _selectedUri.value = null
                _selectedBitmap.value = bitmap
                _imageMetadata.value = metadata
                _extractedResult.value = null
                _editableText.value = ""
                _currentSavedScanId.value = null
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

    fun startScanning(context: Context) {
        val bitmap = _selectedBitmap.value ?: return
        if (_isScanning.value) return // Prevent accidental duplicate scan requests

        _isScanning.value = true
        _scanError.value = null
        _scanStatusMessage.value = "Reading text from your image…"
        navigateTo(Screen.Scanning)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                delay(600) // subtle scan line animation feel
                withContext(Dispatchers.Main) {
                    _scanStatusMessage.value = "Analyzing layout and detecting text blocks…"
                }
                delay(600)

                val result = OcrEngine.recognizeText(bitmap)

                withContext(Dispatchers.Main) {
                    _scanStatusMessage.value = "Finalizing text extraction…"
                }
                delay(300)

                // Attempt saving image and thumbnail to local storage with quota resilience
                var savedPath: String? = null
                var thumbPath: String? = null
                try {
                    savedPath = ImageUtils.saveBitmapToInternalStorage(context, bitmap)
                    thumbPath = ImageUtils.createAndSaveThumbnail(context, bitmap)
                } catch (storageEx: Exception) {
                    Log.e("MainViewModel", "Local image persistence failed: ${storageEx.message}")
                }

                val originalFilename = _imageMetadata.value?.filename ?: "scan_${System.currentTimeMillis()}.jpg"
                val defaultTitle = if (result.fullText.isNotBlank()) {
                    val firstLine = result.fullText.lines().firstOrNull { it.isNotBlank() } ?: originalFilename
                    if (firstLine.length > 32) firstLine.take(32) + "…" else firstLine
                } else {
                    "Scan ${SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())}"
                }

                val entity = ScanEntity(
                    title = defaultTitle,
                    extractedText = result.fullText,
                    imagePath = savedPath,
                    thumbnailPath = thumbPath,
                    originalFilename = originalFilename,
                    timestamp = System.currentTimeMillis(),
                    characterCount = result.characterCount,
                    wordCount = result.wordCount,
                    lineCount = result.lineCount,
                    fileSizeFormatted = _imageMetadata.value?.formattedSize ?: "Direct",
                    imageDimensions = _imageMetadata.value?.dimensions ?: "${bitmap.width} × ${bitmap.height}",
                    confidenceNote = result.confidenceNote,
                    languageDetected = result.languageDetected
                )

                val id = try {
                    scanDao.insertScan(entity)
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Database save error: ${e.message}")
                    null
                }

                withContext(Dispatchers.Main) {
                    _isScanning.value = false
                    _extractedResult.value = result
                    _editableText.value = result.fullText
                    _currentSavedScanId.value = id
                    _isEditMode.value = false
                    updateStorageInfo()
                    // Keep the completed scan on the scanning route; the UI switches to selectable OCR text in place.
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isScanning.value = false
                    _scanError.value = e.localizedMessage ?: "Failed to read text. Please check image quality."
                }
            }
        }
    }

    fun retryScanning(context: Context) {
        startScanning(context)
    }

    fun toggleEditMode() {
        _isEditMode.value = !_isEditMode.value
    }

    fun onTextEdited(newText: String) {
        _editableText.value = newText
        val words = if (newText.isBlank()) 0 else newText.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        val lines = if (newText.isBlank()) 0 else newText.lines().size
        val current = _extractedResult.value
        _extractedResult.value = current?.copy(
            fullText = newText,
            characterCount = newText.length,
            wordCount = words,
            lineCount = lines
        ) ?: OcrResult(
            fullText = newText,
            characterCount = newText.length,
            wordCount = words,
            lineCount = lines
        )
    }

    fun saveEditsToHistory() {
        val currentId = _currentSavedScanId.value ?: return
        val updatedText = _editableText.value
        val result = _extractedResult.value ?: return

        viewModelScope.launch(Dispatchers.IO) {
            val existing = scanDao.getScanById(currentId) ?: return@launch
            val updated = existing.copy(
                extractedText = updatedText,
                characterCount = result.characterCount,
                wordCount = result.wordCount,
                lineCount = result.lineCount
            )
            scanDao.updateScan(updated)
            withContext(Dispatchers.Main) {
                _isEditMode.value = false
            }
        }
    }

    fun reopenScan(scan: ScanEntity, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val bitmap = scan.imagePath?.let { ImageUtils.loadBitmapFromFile(it) }
            withContext(Dispatchers.Main) {
                _selectedBitmap.value = bitmap
                _selectedUri.value = null
                _imageMetadata.value = ImageMetadata(
                    filename = scan.originalFilename.ifBlank { scan.title },
                    dimensions = scan.imageDimensions,
                    formattedSize = scan.fileSizeFormatted,
                    format = "JPEG"
                )
                _extractedResult.value = OcrResult(
                    fullText = scan.extractedText,
                    characterCount = scan.characterCount,
                    wordCount = scan.wordCount,
                    lineCount = scan.lineCount,
                    confidenceNote = scan.confidenceNote,
                    languageDetected = scan.languageDetected.ifBlank { "Auto-detected" },
                    isAiModel = true
                )
                _editableText.value = scan.extractedText
                _currentSavedScanId.value = scan.id
                _isEditMode.value = false
                navigateTo(Screen.Result)
            }
        }
    }

    fun deleteCurrentScan() {
        val currentId = _currentSavedScanId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val scan = scanDao.getScanById(currentId)
            scan?.let {
                it.imagePath?.let { path -> try { File(path).delete() } catch (_: Exception) {} }
                it.thumbnailPath?.let { path -> try { File(path).delete() } catch (_: Exception) {} }
            }
            scanDao.deleteById(currentId)
            withContext(Dispatchers.Main) {
                _currentSavedScanId.value = null
                updateStorageInfo()
                navigateTo(Screen.Home, clearStack = true)
            }
        }
    }

    fun deleteScan(scan: ScanEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            scanDao.deleteScan(scan)
            scan.imagePath?.let { path -> try { File(path).delete() } catch (_: Exception) {} }
            scan.thumbnailPath?.let { path -> try { File(path).delete() } catch (_: Exception) {} }
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
