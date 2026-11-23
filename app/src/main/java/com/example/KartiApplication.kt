package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.example.data.local.AppDatabase
import com.example.data.local.CardHistoryRepository
import com.example.data.local.SettingsDataStore

class KartiApplication : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val cardHistoryRepository: CardHistoryRepository by lazy {
        CardHistoryRepository(database.cardHistoryDao())
    }
    val settingsDataStore: SettingsDataStore by lazy { SettingsDataStore(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        val bubbleChannel = NotificationChannel(
            CHANNEL_BUBBLE,
            getString(R.string.channel_bubble_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_bubble_desc)
            setShowBadge(false)
        }

        val scannerChannel = NotificationChannel(
            CHANNEL_SCANNER,
            getString(R.string.channel_scanner_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_scanner_desc)
            setShowBadge(false)
        }

        manager.createNotificationChannel(bubbleChannel)
        manager.createNotificationChannel(scannerChannel)
    }

    companion object {
        const val CHANNEL_BUBBLE = "karti_bubble_channel"
        const val CHANNEL_SCANNER = "karti_scanner_channel"
    }
}
