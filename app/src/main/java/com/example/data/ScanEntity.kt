package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scans")
data class ScanEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val extractedText: String,
    val imagePath: String? = null,
    val thumbnailPath: String? = null,
    val originalFilename: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val characterCount: Int = 0,
    val wordCount: Int = 0,
    val lineCount: Int = 0,
    val fileSizeFormatted: String = "",
    val imageDimensions: String = "",
    val confidenceNote: String = "",
    val languageDetected: String = "",
    // Groups pages that came from the same multi-image pick or multi-page document import.
    // Null for a single standalone scan (camera photo, single gallery pick, sample doc).
    val batchId: String? = null,
    // 0-based position of this page within its batch. Ignored when batchId is null.
    val pageIndex: Int = 0,
    // Total number of pages in the batch this entry belongs to (denormalized for display
    // convenience in History, e.g. "Page 2 of 5"). Ignored when batchId is null.
    val batchPageCount: Int = 1
)