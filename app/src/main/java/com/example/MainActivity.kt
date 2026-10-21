package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.AppThemeMode
import com.example.ocr.SampleDocumentType
import com.example.ui.MainViewModel
import com.example.ui.Screen
import com.example.ui.components.AppBottomBar
import com.example.ui.components.ImageSourceSheet
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ImagePreviewScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.ResultScreen
import com.example.ui.screens.ScanningScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.SnapTextTheme
import com.example.util.configureAppCheck
import java.io.File

class MainActivity : ComponentActivity() {
    var incomingShareIntent by mutableStateOf<Intent?>(null)
        private set

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingShareIntent = intent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Configure App Check with launch intent before any UI or Firebase operations
        configureAppCheck(this, intent)
        incomingShareIntent = intent.takeIf { it.action == Intent.ACTION_SEND || it.action == Intent.ACTION_SEND_MULTIPLE }
        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = viewModel()
            SnapTextTheme(darkTheme = isSystemInDarkTheme()) {
                SnapTextApp(
                viewModel = viewModel,
                incomingShareIntent = incomingShareIntent,
                onShareIntentHandled = { incomingShareIntent = null }
            )
            }
        }
    }
}

@Composable
fun SnapTextApp(
    viewModel: MainViewModel,
    incomingShareIntent: Intent? = null,
    onShareIntentHandled: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    LaunchedEffect(incomingShareIntent) {
        val sharedIntent = incomingShareIntent
        if (sharedIntent != null) {
            val sharedUri = if (sharedIntent.action == Intent.ACTION_SEND) {
                @Suppress("DEPRECATION")
                sharedIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    ?: sharedIntent.clipData?.getItemAt(0)?.uri
            } else {
                @Suppress("DEPRECATION")
                val streams = sharedIntent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                streams?.firstOrNull() ?: sharedIntent.clipData?.getItemAt(0)?.uri
            }
            val declaredType = sharedIntent.type
            val resolvedType = sharedUri?.let { uri ->
                runCatching { context.contentResolver.getType(uri) }.getOrNull()
            }
            val isImage = declaredType?.startsWith("image/") == true ||
                resolvedType?.startsWith("image/") == true

            when {
                sharedUri == null -> Toast.makeText(
                    context,
                    "Could not open the shared image. Try sharing an image file directly.",
                    Toast.LENGTH_LONG
                ).show()
                !isImage -> Toast.makeText(
                    context,
                    "This file type is not supported yet. Share an image such as JPG, PNG or WebP.",
                    Toast.LENGTH_LONG
                ).show()
                else -> viewModel.onImagePicked(sharedUri, context, autoScan = true)
            }
            onShareIntentHandled()
        }
    }

    val currentScreen by viewModel.currentScreen.collectAsState()
    val recentScans by viewModel.recentScans.collectAsState()
    val allScans by viewModel.filteredScans.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val storageInfo by viewModel.storageInfo.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()

    val selectedBitmap by viewModel.selectedBitmap.collectAsState()
    val imageMetadata by viewModel.imageMetadata.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val scanStatusMessage by viewModel.scanStatusMessage.collectAsState()
    val scanError by viewModel.scanError.collectAsState()
    val extractedResult by viewModel.extractedResult.collectAsState()
    val editableText by viewModel.editableText.collectAsState()
    val isEditMode by viewModel.isEditMode.collectAsState()

    var showImageSourceSheet by remember { mutableStateOf(false) }
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }

    // Modern zero-permission photo picker for Gallery
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { viewModel.onImagePicked(it, context) }
    }

    // Camera photo capture launcher with FileProvider URI
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success) {
            tempCameraUri?.let { uri ->
                viewModel.onImagePicked(uri, context)
            }
        }
    }

    fun launchCamera() {
        try {
            val cacheDir = File(context.cacheDir, "camera_photos")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val photoFile = File(cacheDir, "snaptext_camera_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                photoFile
            )
            tempCameraUri = uri
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot open camera: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            launchCamera()
        } else {
            Toast.makeText(context, "Camera permission needed to take photos", Toast.LENGTH_SHORT).show()
        }
    }

    fun checkAndLaunchCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val isMainTabScreen = currentScreen is Screen.Home ||
            currentScreen is Screen.History ||
            currentScreen is Screen.Settings

    Scaffold(
        bottomBar = {
            if (isMainTabScreen) {
                AppBottomBar(
                    currentScreen = currentScreen,
                    onNavigate = { viewModel.navigateTo(it) },
                    onQuickScanClick = { showImageSourceSheet = true }
                )
            }
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isMainTabScreen) innerPadding else androidx.compose.foundation.layout.PaddingValues())
        ) {
            when (val screen = currentScreen) {
                is Screen.Onboarding -> {
                    OnboardingScreen(
                        onComplete = { viewModel.completeOnboarding() }
                    )
                }

                is Screen.Home -> {
                    HomeScreen(
                        recentScans = recentScans,
                        onExtractTextClick = { showImageSourceSheet = true },
                        onUploadImageClick = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onTakePhotoClick = { checkAndLaunchCamera() },
                        onSampleSelect = { sample ->
                            viewModel.onSamplePicked(sample, context)
                        },
                        onReopenScan = { scan ->
                            viewModel.reopenScan(scan, context)
                        },
                        onViewAllScans = { viewModel.navigateTo(Screen.History) },
                        onNavigateToSettings = { viewModel.navigateTo(Screen.Settings) }
                    )
                }

                is Screen.History -> {
                    HistoryScreen(
                        scans = allScans,
                        searchQuery = searchQuery,
                        onSearchQueryChange = { viewModel.setSearchQuery(it) },
                        onReopenScan = { scan -> viewModel.reopenScan(scan, context) },
                        onDeleteScan = { scan -> viewModel.deleteScan(scan) },
                        onBack = { viewModel.handleBack() }
                    )
                }

                is Screen.Settings -> {
                    SettingsScreen(
                        currentTheme = themeMode,
                        onThemeSelect = { viewModel.setThemeMode(it) },
                        storageInfo = storageInfo,
                        onClearAllScans = { viewModel.clearAllScans() },
                        onBack = { viewModel.handleBack() }
                    )
                }

                is Screen.ImagePreview -> {
                    ImagePreviewScreen(
                        bitmap = selectedBitmap,
                        metadata = imageMetadata,
                        onChangeImage = { showImageSourceSheet = true },
                        onRotateImage = { viewModel.rotateSelectedImage() },
                        onCropImage = { viewModel.cropSelectedImage() },
                        onScanForText = { viewModel.startScanning(context) },
                        onBack = { viewModel.handleBack() }
                    )
                }

                is Screen.Scanning -> {
                    if (!isScanning && extractedResult != null && scanError == null) {
                        ResultScreen(
                            originalBitmap = selectedBitmap,
                            ocrResult = extractedResult,
                            editableText = editableText,
                            isEditMode = isEditMode,
                            onToggleEditMode = { viewModel.toggleEditMode() },
                            onTextChange = { viewModel.onTextEdited(it) },
                            onSaveEdits = { viewModel.saveEditsToHistory() },
                            onScanAnother = { showImageSourceSheet = true },
                            onDeleteScan = { viewModel.deleteCurrentScan() },
                            onBack = { viewModel.handleBack() }
                        )
                    } else {
                        ScanningScreen(
                            bitmap = selectedBitmap,
                            statusMessage = scanStatusMessage,
                            error = scanError,
                            onRetry = { viewModel.retryScanning(context) },
                            onBack = { viewModel.handleBack() }
                        )
                    }
                }

                is Screen.Result -> {
                    ResultScreen(
                        originalBitmap = selectedBitmap,
                        ocrResult = extractedResult,
                        editableText = editableText,
                        isEditMode = isEditMode,
                        onToggleEditMode = { viewModel.toggleEditMode() },
                        onTextChange = { viewModel.onTextEdited(it) },
                        onSaveEdits = { viewModel.saveEditsToHistory() },
                        onScanAnother = { showImageSourceSheet = true },
                        onDeleteScan = { viewModel.deleteCurrentScan() },
                        onBack = { viewModel.handleBack() }
                    )
                }
            }
        }

        // Image source bottom sheet
        if (showImageSourceSheet) {
            ImageSourceSheet(
                onDismiss = { showImageSourceSheet = false },
                onCameraSelect = { checkAndLaunchCamera() },
                onGallerySelect = {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onSampleSelect = {
                    viewModel.onSamplePicked(SampleDocumentType.CONTRACT, context)
                }
            )
        }
    }
}
