package com.example.data.local

import kotlinx.coroutines.flow.Flow

class CardHistoryRepository(
    private val dao: CardHistoryDao
) {
    val allCards: Flow<List<CardScanEntity>> = dao.getAllCards()

    suspend fun saveScannedCard(
        cardCode: String,
        networkName: String,
        isAutoSubmitted: Boolean = false,
        confidence: Float = 1.0f
    ) {
        dao.insertCard(
            CardScanEntity(
                cardCode = cardCode,
                networkName = networkName,
                scannedAt = System.currentTimeMillis(),
                isAutoSubmitted = isAutoSubmitted,
                confidence = confidence
            )
        )
    }

    suspend fun markSubmitted(cardCode: String) {
        dao.markCardSubmitted(cardCode)
    }

    suspend fun deleteCard(id: Long) {
        dao.deleteCardById(id)
    }

    suspend fun clearAll() {
        dao.clearAll()
    }
}
