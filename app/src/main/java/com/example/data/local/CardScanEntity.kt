package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scanned_cards")
data class CardScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardCode: String,
    val networkName: String = "",
    val scannedAt: Long = System.currentTimeMillis(),
    val isAutoSubmitted: Boolean = false,
    val confidence: Float = 1.0f
)
