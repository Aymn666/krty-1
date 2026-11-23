package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CardHistoryDao {
    @Query("SELECT * FROM scanned_cards ORDER BY scannedAt DESC")
    fun getAllCards(): Flow<List<CardScanEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCard(card: CardScanEntity): Long

    @Query("UPDATE scanned_cards SET isAutoSubmitted = 1 WHERE cardCode = :code")
    suspend fun markCardSubmitted(code: String)

    @Query("DELETE FROM scanned_cards WHERE id = :id")
    suspend fun deleteCardById(id: Long)

    @Query("DELETE FROM scanned_cards")
    suspend fun clearAll()
}
