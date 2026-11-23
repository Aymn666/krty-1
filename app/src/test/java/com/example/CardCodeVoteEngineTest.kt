package com.example

import com.example.ocr.CandidateObservation
import com.example.ocr.CardCodeVoteEngine
import com.example.ocr.OcrScanState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardCodeVoteEngineTest {

    @Test
    fun locksNumericAndAlphanumericYemenCardsAcrossFrames() {
        val codes = listOf("6891077607", "6l6gns7w", "2990885685", "515292830", "2000436102")
        for (expected in codes) {
            val engine = CardCodeVoteEngine(windowSize = 7, requiredVotes = 3)
            var lastState: OcrScanState = OcrScanState.Scanning()
            repeat(3) {
                lastState = engine.processFrame(
                    observations = listOf(
                        CandidateObservation(
                            text = expected,
                            normalizedHeight = 0.12f,
                            inCenterRoi = true,
                            mlKitConfidence = 0.88f
                        ),
                        // Support phone number at bottom of card should not beat the centered card code
                        CandidateObservation(
                            text = "0774100051",
                            normalizedHeight = 0.03f,
                            inCenterRoi = false,
                            mlKitConfidence = 0.90f
                        ),
                        // Banner word should be ignored
                        CandidateObservation(
                            text = "internet",
                            normalizedHeight = 0.14f,
                            inCenterRoi = true,
                            mlKitConfidence = 0.95f
                        )
                    ),
                    avgLuma = 120.0
                )
            }
            assertTrue("Expected Locked state for $expected", lastState is OcrScanState.Locked)
            assertEquals(expected, (lastState as OcrScanState.Locked).code)
        }
    }

    @Test
    fun emitsLowLightHintWhenFramesAreDark() {
        val engine = CardCodeVoteEngine()
        var state: OcrScanState = OcrScanState.Scanning()
        repeat(3) {
            state = engine.processFrame(observations = emptyList(), avgLuma = 20.0)
        }
        assertTrue(state is OcrScanState.Scanning)
        assertTrue((state as OcrScanState.Scanning).isLowLight)
    }

    @Test
    fun selectsSingleImageCandidateForGalleryPhoto() {
        val engine = CardCodeVoteEngine()
        val result = engine.selectSingleImageCandidate(
            listOf(
                CandidateObservation(
                    text = "6891077607",
                    normalizedHeight = 0.14f,
                    inCenterRoi = true,
                    mlKitConfidence = 0.91f
                ),
                CandidateObservation(
                    text = "0774100051",
                    normalizedHeight = 0.03f,
                    inCenterRoi = false,
                    mlKitConfidence = 0.89f
                )
            )
        )
        assertTrue(result is OcrScanState.Locked)
        assertEquals("6891077607", (result as OcrScanState.Locked).code)
    }
}
