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
    val languageDetected: String = ""
)
