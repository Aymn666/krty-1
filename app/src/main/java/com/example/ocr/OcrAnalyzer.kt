package com.example.ocr

import android.graphics.Rect
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class OcrAnalyzer(
    private val analyzeIntervalMs: Long = 300L,
    private val onStateChanged: (OcrScanState) -> Unit
) : ImageAnalysis.Analyzer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val voteEngine = CardCodeVoteEngine()
    private val isProcessing = AtomicBoolean(false)
    private val isLocked = AtomicBoolean(false)
    private var lastAnalyzeTimestampMs = 0L

    fun reset() {
        isLocked.set(false)
        voteEngine.reset()
        onStateChanged(OcrScanState.Scanning())
    }

    fun close() {
        recognizer.close()
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (isLocked.get()) {
            imageProxy.close()
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalyzeTimestampMs < analyzeIntervalMs || !isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            isProcessing.set(false)
            imageProxy.close()
            return
        }

        lastAnalyzeTimestampMs = now
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val avgLuma = estimateAverageLuma(imageProxy.planes[0].buffer)

        val rotatedWidth = if (rotationDegrees == 90 || rotationDegrees == 270) {
            imageProxy.height.toFloat()
        } else {
            imageProxy.width.toFloat()
        }
        val rotatedHeight = if (rotationDegrees == 90 || rotationDegrees == 270) {
            imageProxy.width.toFloat()
        } else {
            imageProxy.height.toFloat()
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                if (!isLocked.get()) {
                    val observations = extractObservations(visionText, rotatedWidth, rotatedHeight)
                    val state = voteEngine.processFrame(observations, avgLuma)
                    if (state is OcrScanState.Locked) {
                        isLocked.set(true)
                    }
                    onStateChanged(state)
                }
            }
            .addOnFailureListener { e ->
                if (!isLocked.get()) {
                    onStateChanged(
                        OcrScanState.Scanning(
                            isLowLight = avgLuma < 42.0,
                            statusMessage = "أعد المسح مع إضاءة أفضل"
                        )
                    )
                }
            }
            .addOnCompleteListener {
                isProcessing.set(false)
                imageProxy.close()
            }
    }

    private fun extractObservations(
        visionText: Text,
        frameWidth: Float,
        frameHeight: Float
    ): List<CandidateObservation> {
        val list = mutableListOf<CandidateObservation>()
        val safeW = frameWidth.coerceAtLeast(1f)
        val safeH = frameHeight.coerceAtLeast(1f)

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                // Check individual elements
                for (element in line.elements) {
                    val box = element.boundingBox ?: continue
                    list.add(
                        CandidateObservation(
                            text = element.text,
                            normalizedHeight = box.height() / safeH,
                            inCenterRoi = isInsideCenterRoi(box, safeW, safeH),
                            mlKitConfidence = element.confidence.takeIf { it > 0f } ?: 0.8f
                        )
                    )
                }
                // Also check space-stripped line when OCR splits a single code into 2 groups
                if (line.elements.size in 2..3) {
                    val collapsed = line.text.replace("\\s+".toRegex(), "")
                    val box = line.boundingBox
                    if (box != null && collapsed.length in 6..12) {
                        list.add(
                            CandidateObservation(
                                text = collapsed,
                                normalizedHeight = box.height() / safeH,
                                inCenterRoi = isInsideCenterRoi(box, safeW, safeH),
                                mlKitConfidence = line.confidence.takeIf { it > 0f } ?: 0.78f
                            )
                        )
                    }
                }
            }
        }
        return list
    }

    private fun isInsideCenterRoi(box: Rect, frameWidth: Float, frameHeight: Float): Boolean {
        val cx = box.centerX() / frameWidth
        val cy = box.centerY() / frameHeight
        return cx in 0.10f..0.90f && cy in 0.22f..0.78f
    }

    private fun estimateAverageLuma(buffer: ByteBuffer): Double {
        val dup = buffer.duplicate()
        dup.rewind()
        val limit = dup.limit()
        if (limit <= 0) return 128.0
        var sum = 0L
        var count = 0
        var i = 0
        val step = 16
        while (i < limit) {
            sum += (dup.get(i).toInt() and 0xFF)
            count++
            i += step
        }
        return if (count == 0) 128.0 else sum.toDouble() / count
    }
}
