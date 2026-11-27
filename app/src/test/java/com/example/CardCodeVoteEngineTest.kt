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
        val codes = listOf(
            "6301679399",
            "7gvmv8d5",
            "6755868974",
            "5853448690",
            "2990885685",
            "2000436102",
            "6l6gns7w",
            "515292830"
        )
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
                            mlKitConfidence = 0.88f,
                            lineText = expected,
                            centerX = 0.5f,
                            centerY = 0.5f
                        ),
                        // Support phone number at bottom of card should not beat the centered card code
                        CandidateObservation(
                            text = "0774166651",
                            normalizedHeight = 0.04f,
                            inCenterRoi = false,
                            mlKitConfidence = 0.90f,
                            lineText = "0774166651 - 774166959",
                            centerX = 0.5f,
                            centerY = 0.85f
                        ),
                        // Serial number & date decoys
                        CandidateObservation(
                            text = "SN1914",
                            normalizedHeight = 0.05f,
                            inCenterRoi = false,
                            mlKitConfidence = 0.92f,
                            lineText = "SN:1914",
                            centerX = 0.2f,
                            centerY = 0.8f
                        ),
                        // IP address decoy
                        CandidateObservation(
                            text = "10001",
                            normalizedHeight = 0.05f,
                            inCenterRoi = false,
                            mlKitConfidence = 0.92f,
                            lineText = "10.0.0.1",
                            centerX = 0.5f,
                            centerY = 0.2f
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
    fun selectsQasemCodeOverPhonePairDecoys() {
        val engine = CardCodeVoteEngine()
        val result = engine.selectMultiPassImageCandidate(
            passes = listOf(
                listOf(
                    CandidateObservation(
                        text = "2000436102",
                        normalizedHeight = 0.10f,
                        inCenterRoi = true,
                        mlKitConfidence = 0.89f,
                        lineText = "2000436102",
                        centerX = 0.5f,
                        centerY = 0.45f
                    ),
                    CandidateObservation(
                        text = "0774166651",
                        normalizedHeight = 0.08f,
                        inCenterRoi = true,
                        mlKitConfidence = 0.94f,
                        lineText = "0774166651 - 774166959",
                        centerX = 0.4f,
                        centerY = 0.65f
                    ),
                    CandidateObservation(
                        text = "774166959",
                        normalizedHeight = 0.08f,
                        inCenterRoi = true,
                        mlKitConfidence = 0.93f,
                        lineText = "0774166651 - 774166959",
                        centerX = 0.6f,
                        centerY = 0.65f
                    )
                ),
                listOf(
                    CandidateObservation(
                        text = "2000436102",
                        normalizedHeight = 0.10f,
                        inCenterRoi = true,
                        mlKitConfidence = 0.91f,
                        lineText = "2000436102",
                        centerX = 0.5f,
                        centerY = 0.45f
                    )
                )
            )
        )
        assertTrue(result is OcrScanState.Locked)
        assertEquals("2000436102", (result as OcrScanState.Locked).code)
    }

    @Test
    fun selectsZainNet9DigitCodeOverPhoneAndIpDecoys() {
        val engine = CardCodeVoteEngine()
        val result = engine.selectSingleImageCandidate(
            listOf(
                CandidateObservation(
                    text = "515292830",
                    normalizedHeight = 0.09f,
                    inCenterRoi = true,
                    mlKitConfidence = 0.86f,
                    lineText = "515292830",
                    centerX = 0.5f,
                    centerY = 0.55f
                ),
                CandidateObservation(
                    text = "776107164",
                    normalizedHeight = 0.04f,
                    inCenterRoi = true,
                    mlKitConfidence = 0.92f,
                    lineText = "776107164/770000000",
                    centerX = 0.5f,
                    centerY = 0.35f
                ),
                CandidateObservation(
                    text = "10Jun26",
                    normalizedHeight = 0.04f,
                    inCenterRoi = false,
                    mlKitConfidence = 0.90f,
                    lineText = "10.Jun.26"
                ),
                CandidateObservation(
                    text = "500MB",
                    normalizedHeight = 0.05f,
                    inCenterRoi = false,
                    mlKitConfidence = 0.92f,
                    lineText = "500MB"
                )
            )
        )
        assertTrue(result is OcrScanState.Locked)
        assertEquals("515292830", (result as OcrScanState.Locked).code)
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
                    text = "6301679399",
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
        assertEquals("6301679399", (result as OcrScanState.Locked).code)
    }

    @Test
    fun burstCaptureLocksImmediatelyWithAlternatives() {
        val engine = CardCodeVoteEngine()
        val result = engine.processMultiPassFrame(
            passes = listOf(
                listOf(
                    CandidateObservation(
                        text = "6755868974",
                        normalizedHeight = 0.12f,
                        inCenterRoi = true,
                        mlKitConfidence = 0.88f
                    ),
                    CandidateObservation(
                        text = "6755868971",
                        normalizedHeight = 0.12f,
                        inCenterRoi = true,
                        mlKitConfidence = 0.65f
                    )
                ),
                listOf(
                    CandidateObservation(
                        text = "6755868974",
                        normalizedHeight = 0.12f,
                        inCenterRoi = true,
                        mlKitConfidence = 0.90f
                    )
                )
            ),
            avgLuma = 110.0,
            burstCapture = true
        )
        assertTrue(result is OcrScanState.Locked)
        val locked = result as OcrScanState.Locked
        assertEquals("6755868974", locked.code)
        assertTrue(locked.alternatives.contains("6755868971"))
    }

    @Test
    fun fastDualPassLocksOnSingleFrameAndTwoFramesLockByDefault() {
        val dualEngine = CardCodeVoteEngine()
        val obs = listOf(
            CandidateObservation(
                text = "6301679399",
                normalizedHeight = 0.14f,
                inCenterRoi = true,
                mlKitConfidence = 0.88f,
                lineText = "6301679399",
                centerX = 0.5f,
                centerY = 0.5f
            )
        )
        val instant = dualEngine.processMultiPassFrame(
            passes = listOf(obs, obs),
            avgLuma = 120.0,
            burstCapture = false
        )
        assertTrue(instant is OcrScanState.Locked)
        assertEquals("6301679399", (instant as OcrScanState.Locked).code)

        val twoFrameEngine = CardCodeVoteEngine()
        val frame1 = twoFrameEngine.processFrame(obs, avgLuma = 120.0)
        assertTrue(frame1 is OcrScanState.Scanning)
        val frame2 = twoFrameEngine.processFrame(obs, avgLuma = 120.0)
        assertTrue(frame2 is OcrScanState.Locked)
        assertEquals("6301679399", (frame2 as OcrScanState.Locked).code)
    }

    @Test
    fun ignoresPricesQuotasValiditySerialsAndDatesWhilePickingCenteredCode() {
        val engine = CardCodeVoteEngine()
        val result = engine.selectSingleImageCandidate(
            listOf(
                CandidateObservation(
                    text = "6l6gns7w",
                    normalizedHeight = 0.11f,
                    inCenterRoi = true,
                    mlKitConfidence = 0.89f,
                    lineText = "6l6gns7w",
                    centerX = 0.5f,
                    centerY = 0.5f
                ),
                CandidateObservation(
                    text = "1000RY",
                    normalizedHeight = 0.08f,
                    inCenterRoi = false,
                    mlKitConfidence = 0.95f,
                    lineText = "السعر 1000RY"
                ),
                CandidateObservation(
                    text = "2500MB",
                    normalizedHeight = 0.08f,
                    inCenterRoi = true,
                    mlKitConfidence = 0.94f,
                    lineText = "2500MB سعة التحميل"
                ),
                CandidateObservation(
                    text = "40HOURS",
                    normalizedHeight = 0.08f,
                    inCenterRoi = false,
                    mlKitConfidence = 0.94f,
                    lineText = "الصلاحية 40HOURS"
                ),
                CandidateObservation(
                    text = "19142830",
                    normalizedHeight = 0.08f,
                    inCenterRoi = true,
                    mlKitConfidence = 0.95f,
                    lineText = "SN: 19142830"
                ),
                CandidateObservation(
                    text = "20260610",
                    normalizedHeight = 0.07f,
                    inCenterRoi = false,
                    mlKitConfidence = 0.95f,
                    lineText = "2026/06/10"
                ),
                CandidateObservation(
                    text = "10Jun26",
                    normalizedHeight = 0.07f,
                    inCenterRoi = false,
                    mlKitConfidence = 0.95f,
                    lineText = "10.Jun.26"
                )
            )
        )
        assertTrue(result is OcrScanState.Locked)
        val locked = result as OcrScanState.Locked
        assertEquals("6l6gns7w", locked.code)
        assertTrue(locked.alternatives.isEmpty())
    }
}
