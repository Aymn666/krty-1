package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.kartiDataStore: DataStore<Preferences> by preferencesDataStore(name = "karti_settings")

data class KartiSettings(
    val autoFlashEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val autoFillEnabled: Boolean = true,
    val autoClickEnabled: Boolean = true,
    val saveHistoryEnabled: Boolean = true,
    val floatingBubbleEnabled: Boolean = true,
    val lastNetworkName: String = ""
)

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val AUTO_FLASH = booleanPreferencesKey("auto_flash_enabled")
        val VIBRATION = booleanPreferencesKey("vibration_enabled")
        val AUTO_FILL = booleanPreferencesKey("auto_fill_enabled")
        val AUTO_CLICK = booleanPreferencesKey("auto_click_enabled")
        val SAVE_HISTORY = booleanPreferencesKey("save_history_enabled")
        val FLOATING_BUBBLE = booleanPreferencesKey("floating_bubble_enabled")
        val LAST_NETWORK = stringPreferencesKey("last_network_name")
    }

    val settingsFlow: Flow<KartiSettings> = context.kartiDataStore.data.map { prefs ->
        KartiSettings(
            autoFlashEnabled = prefs[Keys.AUTO_FLASH] ?: true,
            vibrationEnabled = prefs[Keys.VIBRATION] ?: true,
            autoFillEnabled = prefs[Keys.AUTO_FILL] ?: true,
            autoClickEnabled = prefs[Keys.AUTO_CLICK] ?: true,
            saveHistoryEnabled = prefs[Keys.SAVE_HISTORY] ?: true,
            floatingBubbleEnabled = prefs[Keys.FLOATING_BUBBLE] ?: true,
            lastNetworkName = prefs[Keys.LAST_NETWORK] ?: ""
        )
    }

    suspend fun setAutoFlash(enabled: Boolean) {
        context.kartiDataStore.edit { it[Keys.AUTO_FLASH] = enabled }
    }

    suspend fun setVibration(enabled: Boolean) {
        context.kartiDataStore.edit { it[Keys.VIBRATION] = enabled }
    }

    suspend fun setAutoFill(enabled: Boolean) {
        context.kartiDataStore.edit { it[Keys.AUTO_FILL] = enabled }
    }

    suspend fun setAutoClick(enabled: Boolean) {
        context.kartiDataStore.edit { it[Keys.AUTO_CLICK] = enabled }
    }

    suspend fun setSaveHistory(enabled: Boolean) {
        context.kartiDataStore.edit { it[Keys.SAVE_HISTORY] = enabled }
    }

    suspend fun setFloatingBubble(enabled: Boolean) {
        context.kartiDataStore.edit { it[Keys.FLOATING_BUBBLE] = enabled }
    }

    suspend fun setLastNetworkName(name: String) {
        context.kartiDataStore.edit { it[Keys.LAST_NETWORK] = name }
    }
}
