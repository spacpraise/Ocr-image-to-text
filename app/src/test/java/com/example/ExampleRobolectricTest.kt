package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.AppThemeMode
import com.example.data.PreferencesManager
import com.example.data.ScanEntity
import com.example.ocr.ImageUtils
import com.example.ocr.SampleDocumentType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context verifies SnapText app name`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("SnapText", appName)
    }

    @Test
    fun `preferences manager persists onboarding and theme`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PreferencesManager(context)

        prefs.isOnboardingCompleted = true
        assertTrue(prefs.isOnboardingCompleted)

        prefs.themeMode = AppThemeMode.DARK
        assertEquals(AppThemeMode.DARK, prefs.themeMode)
    }

    @Test
    fun `sample document bitmap generation produces valid dimensions`() {
        val bitmap = ImageUtils.createSampleDocumentBitmap(SampleDocumentType.CONTRACT)
        assertNotNull(bitmap)
        assertTrue(bitmap.width > 0)
        assertTrue(bitmap.height > 0)
    }

    @Test
    fun `thumbnail generation scales bitmap and saves to file`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = ImageUtils.createSampleDocumentBitmap(SampleDocumentType.INVOICE)
        val path = ImageUtils.createAndSaveThumbnail(context, bitmap, 200)

        assertNotNull(path)
        val file = File(path)
        assertTrue(file.exists())
        assertTrue(file.length() > 0)
    }

    @Test
    fun `room database stores and retrieves scan with thumbnail and quality metadata`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getDatabase(context)
        val dao = db.scanDao()

        val scan = ScanEntity(
            title = "Test Agreement",
            extractedText = "This is verified extracted text from image.",
            imagePath = "/dummy/path.jpg",
            thumbnailPath = "/dummy/thumb.jpg",
            originalFilename = "contract.png",
            characterCount = 43,
            wordCount = 7,
            lineCount = 1,
            confidenceNote = "High clarity",
            languageDetected = "English"
        )

        val id = dao.insertScan(scan)
        assertTrue(id > 0)

        val retrieved = dao.getScanById(id)
        assertNotNull(retrieved)
        assertEquals("Test Agreement", retrieved?.title)
        assertEquals("This is verified extracted text from image.", retrieved?.extractedText)
        assertEquals("High clarity", retrieved?.confidenceNote)
        assertEquals("English", retrieved?.languageDetected)

        val allScans = dao.getAllScans().first()
        assertTrue(allScans.any { it.id == id })
    }
}
