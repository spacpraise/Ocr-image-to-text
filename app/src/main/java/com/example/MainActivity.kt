package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.AccountSessionManager
import com.example.data.SnapTextAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.example.ui.screens.AccountScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ImagePreviewScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.PremiumPlansSheet
import com.example.ui.screens.ScanningScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SubscriptionPlan
import com.example.ui.theme.SnapTextTheme
import com.example.util.configureAppCheck
import java.io.File

class MainActivity : ComponentActivity() {
    var incomingShareIntent by mutableStateOf<Intent?>(null)
        private set
    var incomingAuthCode by mutableStateOf<String?>(null)
        private set

    private fun readAuthCode(intent: Intent?) {
        val uri = intent?.data
        incomingAuthCode = if (uri?.scheme == "snaptext" && uri.host == "auth" && uri.path == "/callback") {
            uri.getQueryParameter("code")
        } else null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingShareIntent = intent.takeIf { it.action == Intent.ACTION_SEND || it.action == Intent.ACTION_SEND_MULTIPLE }
        readAuthCode(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Configure App Check with launch intent before any UI or Firebase operations
        configureAppCheck(this, intent)
        incomingShareIntent = intent.takeIf { it.action == Intent.ACTION_SEND || it.action == Intent.ACTION_SEND_MULTIPLE }
        readAuthCode(intent)
        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = viewModel()
            val themeMode by viewModel.themeMode.collectAsState()
            val resolvedDarkTheme = when (themeMode) {
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
                AppThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            SnapTextTheme(darkTheme = resolvedDarkTheme) {
                SnapTextApp(
                    viewModel = viewModel,
                    incomingShareIntent = incomingShareIntent,
                    onShareIntentHandled = { incomingShareIntent = null },
                    incomingAuthCode = incomingAuthCode,
                    onAuthCodeHandled = { incomingAuthCode = null }
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
    incomingAuthCode: String? = null,
    onAuthCodeHandled: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sessionManager = remember(context) { AccountSessionManager(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val accountScope = rememberCoroutineScope()
    var connectedAccount by remember { mutableStateOf<SnapTextAccount?>(null) }
    var accountBusy by remember { mutableStateOf(false) }

    fun openAccountWebsite(path: String) {
        val uri = Uri.parse(AccountSessionManager.WEBSITE_URL + path)
        runCatching { CustomTabsIntent.Builder().build().launchUrl(context, uri) }
            .onFailure { Toast.makeText(context, "Could not open the account website.", Toast.LENGTH_LONG).show() }
    }

    fun openConnectWebsite() {
        runCatching {
            val uri = Uri.parse(sessionManager.createConnectionUrl())
            CustomTabsIntent.Builder().build().launchUrl(context, uri)
        }.onFailure {
            Toast.makeText(context, "Could not start secure account connection.", Toast.LENGTH_LONG).show()
        }
    }

    fun handlePdfPicked(uri: Uri) {
        accountScope.launch {
            val account = connectedAccount ?: runCatching {
                withContext(Dispatchers.IO) { sessionManager.refreshAccount() }
            }.getOrNull()
            connectedAccount = account
            if (account?.plan.isNullOrBlank() || account?.plan == "free") {
                Toast.makeText(context, "PDF conversion is a Premium feature. Upgrade to unlock unlimited PDF conversions.", Toast.LENGTH_LONG).show()
                if (account == null) openConnectWebsite() else openAccountWebsite("/account/billing")
                return@launch
            }
            viewModel.onPdfPicked(uri, context)
        }
    }

    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { sessionManager.refreshAccount() } }
            .onSuccess { connectedAccount = it }
            .onFailure { connectedAccount = null }
    }

    LaunchedEffect(incomingAuthCode) {
        val code = incomingAuthCode ?: return@LaunchedEffect
        accountBusy = true
        try {
            connectedAccount = withContext(Dispatchers.IO) { sessionManager.exchangeCode(code) }
            Toast.makeText(context, "SnapText account connected securely.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, e.message ?: "Could not connect your account.", Toast.LENGTH_LONG).show()
        } finally {
            accountBusy = false
            onAuthCodeHandled()
        }
    }

    DisposableEffect(lifecycleOwner, sessionManager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accountScope.launch {
                    runCatching { withContext(Dispatchers.IO) { sessionManager.refreshAccount() } }
                        .onSuccess { connectedAccount = it }
                        .onFailure { connectedAccount = null }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
            val isPdf = declaredType.equals("application/pdf", ignoreCase = true) ||
                resolvedType.equals("application/pdf", ignoreCase = true) ||
                sharedUri?.toString()?.substringBefore('?')?.endsWith(".pdf", ignoreCase = true) == true

            when {
                sharedUri == null -> Toast.makeText(
                    context,
                    "Could not open the shared file. Try sharing the PDF or image directly.",
                    Toast.LENGTH_LONG
                ).show()
                isPdf -> handlePdfPicked(sharedUri)
                isImage -> viewModel.onImagePicked(sharedUri, context, autoScan = true)
                else -> Toast.makeText(
                    context,
                    "This file type is not supported yet. Share a PDF or an image such as JPG, PNG or WebP.",
                    Toast.LENGTH_LONG
                ).show()
            }
            onShareIntentHandled()
        }
    }

    val themeMode by viewModel.themeMode.collectAsState()
    val currentScreen by viewModel.currentScreen.collectAsState()
    val recentScans by viewModel.recentScans.collectAsState()
    val allScans by viewModel.filteredScans.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val storageInfo by viewModel.storageInfo.collectAsState()

    val selectedBitmap by viewModel.selectedBitmap.collectAsState()
    val imageMetadata by viewModel.imageMetadata.collectAsState()
    val pages by viewModel.pages.collectAsState()
    val currentPageIndex by viewModel.currentPageIndex.collectAsState()
    val docImportError by viewModel.docImportError.collectAsState()

    var showImageSourceSheet by remember { mutableStateOf(false) }
    var showPremiumSheet by remember { mutableStateOf(false) }
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(docImportError) {
        docImportError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearDocImportError()
        }
    }

    // Single-image photo picker (used from ImagePreviewScreen's "Change" action, etc.)
    val galleryLauncherSingle = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { viewModel.onImagePicked(it, context) }
    }

    // Multi-image photo picker (gallery entry point from the source sheet)
    val galleryLauncherMultiple = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            if (uris.size == 1) {
                viewModel.onImagePicked(uris.first(), context)
            } else {
                viewModel.onImagesPicked(uris, context)
            }
        }
    }

    // Document (PDF) picker
    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            if (mimeType == "application/pdf" || uri.toString().endsWith(".pdf", ignoreCase = true)) {
                handlePdfPicked(uri)
            } else {
                viewModel.onUnsupportedDocumentPicked()
            }
        }
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
                            galleryLauncherMultiple.launch(
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
                        onNavigateToSettings = { viewModel.navigateTo(Screen.Settings) },
                        onUpgradeClick = { if (connectedAccount == null) openConnectWebsite() else openAccountWebsite("/account/billing") },
                        planName = if (connectedAccount?.plan != null && connectedAccount?.plan != "free") "Premium" else "Free"
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
                        onBack = { viewModel.handleBack() },
                        onUpgradeClick = { openAccountWebsite(if (connectedAccount == null) "/app-connect" else "/account/billing") },
                        displayName = connectedAccount?.name,
                        email = connectedAccount?.email,
                        onAccountClick = { viewModel.navigateTo(Screen.Account) },
                        onHistoryClick = { viewModel.navigateTo(Screen.History) },
                        onPrivacyClick = { openAccountWebsite("/privacy") },
                        onHelpClick = { Toast.makeText(context, "Help and feedback will be available soon.", Toast.LENGTH_SHORT).show() }
                    )
                }

                is Screen.Account -> {
                    AccountScreen(
                        displayName = connectedAccount?.name,
                        email = connectedAccount?.email,
                        onBack = { viewModel.handleBack() },
                        onUpgradeClick = { openAccountWebsite(if (connectedAccount == null) "/app-connect" else "/account/billing") },
                        onSignInMethodClick = { if (connectedAccount == null) openConnectWebsite() else openAccountWebsite("/account") },
                        onChangePasswordClick = { openAccountWebsite("/forgot-password") },
                        onSignOutClick = {
                            accountBusy = true
                            accountScope.launch {
                                withContext(Dispatchers.IO) { sessionManager.signOut() }
                                connectedAccount = null
                                accountBusy = false
                                openAccountWebsite("/logout")
                                Toast.makeText(context, "Signed out of SnapText.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDeleteAccountClick = { openAccountWebsite("/account") },
                        currentPlan = connectedAccount?.plan ?: "free"
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
                    ScanningScreen(
                        pages = pages,
                        initialPageIndex = currentPageIndex,
                        onPageFocused = { index -> viewModel.onPageFocused(index, context) },
                        onRetryPage = { index -> viewModel.retryPage(index, context) },
                        onDeleteCurrentPage = { viewModel.deleteCurrentPageScan(context) },
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
                    galleryLauncherMultiple.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onDocumentSelect = {
                    documentLauncher.launch(arrayOf("application/pdf"))
                },
                onSampleSelect = {
                    viewModel.onSamplePicked(SampleDocumentType.CONTRACT, context)
                }
            )
        }
        if (showPremiumSheet) {
            PremiumPlansSheet(
                onDismiss = { showPremiumSheet = false },
                onContinue = { selectedPlan: SubscriptionPlan ->
                    showPremiumSheet = false
                    Toast.makeText(
                        context,
                        "${selectedPlan.title} selected. Website checkout will be connected next.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }

    }
}