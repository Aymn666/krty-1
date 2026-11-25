package com.example.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class OcrAnalyzer(
    private val analyzeIntervalMs: Long = 240L,
    private val onStateChanged: (OcrScanState) -> Unit
) : ImageAnalysis.Analyzer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val voteEngine = CardCodeVoteEngine()
    private val isProcessing = AtomicBoolean(false)
    private val isLocked = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)
    private val burstRequested = AtomicBoolean(false)
    private var lastAnalyzeTimestampMs = 0L

    fun reset() {
        isLocked.set(false)
        burstRequested.set(false)
        voteEngine.reset()
        onStateChanged(OcrScanState.Scanning())
    }

    /**
     * Requests an immediate multi-pass burst analysis on the next camera frame
     * (used when the user taps the manual capture button).
     */
    fun requestBurstCapture() {
        isLocked.set(false)
        burstRequested.set(true)
        onStateChanged(
            OcrScanState.Scanning(
                isAnalyzingPhoto = true,
                statusMessage = "جاري التحليل الذكي للإطار..."
            )
        )
    }

    fun close() {
        isClosed.set(true)
        runCatching { recognizer.close() }
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (isClosed.get() || isLocked.get()) {
            imageProxy.close()
            return
        }

        val isBurst = burstRequested.get()
        val now = SystemClock.elapsedRealtime()
        if ((!isBurst && now - lastAnalyzeTimestampMs < analyzeIntervalMs) ||
            !isProcessing.compareAndSet(false, true)
        ) {
            imageProxy.close()
            return
        }

        val patch: CardImagePreprocessor.GrayscalePatch?
        val widePatch: CardImagePreprocessor.GrayscalePatch?
        try {
            if (imageProxy.image == null) {
                isProcessing.set(false)
                imageProxy.close()
                return
            }
            lastAnalyzeTimestampMs = now
            patch = CardImagePreprocessor.extractUprightGrayRoi(imageProxy)
            widePatch = if (isBurst) {
                CardImagePreprocessor.extractUprightGrayRoi(
                    imageProxy = imageProxy,
                    roiLeftFrac = 0.02f,
                    roiTopFrac = 0.08f,
                    roiRightFrac = 0.98f,
                    roiBottomFrac = 0.92f
                )
            } else {
                null
            }
        } finally {
            // Release ImageProxy immediately so CameraX preview never stalls
            imageProxy.close()
        }

        if (patch == null) {
            isProcessing.set(false)
            return
        }

        if (isBurst) {
            burstRequested.set(false)
        }

        val bitmapsToRecycle = mutableListOf<Bitmap>()
        try {
            val contrastBmp = CardImagePreprocessor.toContrastSharpenedBitmap(patch)
            bitmapsToRecycle += contrastBmp
            val adaptiveBmp = CardImagePreprocessor.toAdaptiveThresholdBitmap(patch)
            bitmapsToRecycle += adaptiveBmp

            val taskList = mutableListOf(
                recognizer.process(InputImage.fromBitmap(contrastBmp, 0)),
                recognizer.process(InputImage.fromBitmap(adaptiveBmp, 0))
            )
            val passDims = mutableListOf(
                contrastBmp.width.toFloat() to contrastBmp.height.toFloat(),
                adaptiveBmp.width.toFloat() to adaptiveBmp.height.toFloat()
            )

            if (isBurst) {
                val rawBmp = CardImagePreprocessor.toRawGrayBitmap(patch)
                bitmapsToRecycle += rawBmp
                taskList += recognizer.process(InputImage.fromBitmap(rawBmp, 0))
                passDims += rawBmp.width.toFloat() to rawBmp.height.toFloat()

                if (widePatch != null) {
                    val wideContrast = CardImagePreprocessor.toContrastSharpenedBitmap(widePatch)
                    bitmapsToRecycle += wideContrast
                    taskList += recognizer.process(InputImage.fromBitmap(wideContrast, 0))
                    passDims += wideContrast.width.toFloat() to wideContrast.height.toFloat()
                }
            }

            Tasks.whenAllComplete(taskList)
                .addOnCompleteListener {
                    try {
                        if (!isClosed.get() && !isLocked.get()) {
                            val passes = mutableListOf<List<CandidateObservation>>()
                            taskList.forEachIndexed { idx, task ->
                                if (task.isSuccessful) {
                                    val visionText = task.result
                                    val (w, h) = passDims[idx]
                                    passes += extractObservations(
                                        visionText = visionText,
                                        frameWidth = w,
                                        frameHeight = h,
                                        treatAllAsCenter = idx < 3,
                                        passIndex = idx
                                    )
                                }
                            }

                            val state = voteEngine.processMultiPassFrame(
                                passes = passes,
                                avgLuma = patch.avgLuma,
                                burstCapture = isBurst
                            )
                            if (state is OcrScanState.Locked) {
                                isLocked.set(true)
                            }
                            onStateChanged(state)
                        }
                    } finally {
                        bitmapsToRecycle.forEach { runCatching { it.recycle() } }
                        isProcessing.set(false)
                    }
                }
        } catch (_: Throwable) {
            bitmapsToRecycle.forEach { runCatching { it.recycle() } }
            isProcessing.set(false)
            if (!isClosed.get() && !isLocked.get()) {
                onStateChanged(
                    OcrScanState.Scanning(
                        isLowLight = patch.avgLuma < 42.0,
                        statusMessage = "أعد المسح مع إضاءة أفضل"
                    )
                )
            }
        }
    }

    companion object {
        suspend fun scanImageUri(context: Context, uri: Uri): OcrScanState = withContext(Dispatchers.Default) {
            val sourceBitmap = CardImagePreprocessor.decodeOrientedGalleryBitmap(context, uri)
                ?: return@withContext OcrScanState.Error("تعذر قراءة الصورة المختارة")

            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val voteEngine = CardCodeVoteEngine()

            try {
                val primaryPasses = CardImagePreprocessor.buildPrimaryGalleryPasses(sourceBitmap)
                val allObservations = mutableListOf<List<CandidateObservation>>()

                try {
                    primaryPasses.forEachIndexed { idx, pass ->
                        val visionText = awaitTextRecognition(recognizer, pass.bitmap)
                        if (visionText != null) {
                            allObservations += extractObservations(
                                visionText = visionText,
                                frameWidth = pass.bitmap.width.toFloat(),
                                frameHeight = pass.bitmap.height.toFloat(),
                                treatAllAsCenter = pass.isCenterCrop,
                                passIndex = idx
                            )
                        }
                    }
                } finally {
                    primaryPasses.forEach { pass -> runCatching { pass.bitmap.recycle() } }
                }

                var result = voteEngine.selectMultiPassImageCandidate(
                    passes = allObservations,
                    sourceLabel = "المعرض"
                )

                // Fallback rotations if card photo was saved sideways or upside down
                if (result is OcrScanState.Error) {
                    val rotatedObservations = mutableListOf<List<CandidateObservation>>()
                    val angles = listOf(90f, 270f, 180f)
                    angles.forEachIndexed { idx, angle ->
                        val rotatedBmp = CardImagePreprocessor.buildRotatedContrastPass(sourceBitmap, angle)
                        try {
                            val visionText = awaitTextRecognition(recognizer, rotatedBmp)
                            if (visionText != null) {
                                rotatedObservations += extractObservations(
                                    visionText = visionText,
                                    frameWidth = rotatedBmp.width.toFloat(),
                                    frameHeight = rotatedBmp.height.toFloat(),
                                    treatAllAsCenter = false,
                                    passIndex = 10 + idx
                                )
                            }
                        } finally {
                            runCatching { rotatedBmp.recycle() }
                        }
                    }
                    if (rotatedObservations.isNotEmpty()) {
                        result = voteEngine.selectMultiPassImageCandidate(
                            passes = rotatedObservations,
                            sourceLabel = "المعرض"
                        )
                    }
                }

                result
            } catch (_: Throwable) {
                OcrScanState.Error("تعذر تحليل الصورة — جرّب صورة أوضح")
            } finally {
                runCatching { sourceBitmap.recycle() }
                runCatching { recognizer.close() }
            }
        }

        private suspend fun awaitTextRecognition(
            recognizer: TextRecognizer,
            bitmap: Bitmap
        ): Text? = suspendCancellableCoroutine { cont ->
            val input = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(input)
                .addOnSuccessListener { visionText ->
                    if (cont.isActive) cont.resume(visionText)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
        }

        private val embeddedTokenRegex = Regex("""[A-Za-z0-9]{6,12}""")

        private fun extractObservations(
            visionText: Text,
            frameWidth: Float,
            frameHeight: Float,
            treatAllAsCenter: Boolean = false,
            passIndex: Int = 0
        ): List<CandidateObservation> {
            val list = mutableListOf<CandidateObservation>()
            val safeW = frameWidth.coerceAtLeast(1f)
            val safeH = frameHeight.coerceAtLeast(1f)

            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    val lineText = line.text
                    val lineBox = line.boundingBox

                    for (element in line.elements) {
                        val box = element.boundingBox ?: continue
                        val cx = (box.centerX() / safeW).coerceIn(0f, 1f)
                        val cy = (box.centerY() / safeH).coerceIn(0f, 1f)
                        val inRoi = treatAllAsCenter || isInsideCenterRoi(box, safeW, safeH)
                        val conf = element.confidence.takeIf { it > 0f } ?: 0.82f

                        list.add(
                            CandidateObservation(
                                text = element.text,
                                normalizedHeight = box.height() / safeH,
                                inCenterRoi = inRoi,
                                mlKitConfidence = conf,
                                lineText = lineText,
                                centerX = cx,
                                centerY = cy,
                                passIndex = passIndex
                            )
                        )
                    }

                    // Extract alphanumeric segments embedded inside punctuated tokens (e.g., ":6301679399" or "[515292830]")
                    if (lineBox != null) {
                        val lineCx = (lineBox.centerX() / safeW).coerceIn(0f, 1f)
                        val lineCy = (lineBox.centerY() / safeH).coerceIn(0f, 1f)
                        val lineInRoi = treatAllAsCenter || isInsideCenterRoi(lineBox, safeW, safeH)
                        val lineConf = line.confidence.takeIf { it > 0f } ?: 0.80f

                        for (match in embeddedTokenRegex.findAll(lineText)) {
                            val token = match.value
                            if (line.elements.none { it.text == token }) {
                                list.add(
                                    CandidateObservation(
                                        text = token,
                                        normalizedHeight = lineBox.height() / safeH,
                                        inCenterRoi = lineInRoi,
                                        mlKitConfidence = lineConf,
                                        lineText = lineText,
                                        centerX = lineCx,
                                        centerY = lineCy,
                                        passIndex = passIndex
                                    )
                                )
                            }
                        }

                        // Collapse split digits/alphanumerics ONLY if separated purely by whitespace
                        if (line.elements.size in 2..3 && lineText.all { it.isLetterOrDigit() || it.isWhitespace() }) {
                            val collapsed = lineText.replace("\\s+".toRegex(), "")
                            if (collapsed.length in 6..12) {
                                list.add(
                                    CandidateObservation(
                                        text = collapsed,
                                        normalizedHeight = lineBox.height() / safeH,
                                        inCenterRoi = lineInRoi,
                                        mlKitConfidence = (lineConf - 0.03f).coerceAtLeast(0.5f),
                                        lineText = lineText,
                                        centerX = lineCx,
                                        centerY = lineCy,
                                        passIndex = passIndex
                                    )
                                )
                            }
                        }
                    }
                }
            }
            return list
        }

        private fun isInsideCenterRoi(box: Rect, frameWidth: Float, frameHeight: Float): Boolean {
            val cx = box.centerX() / frameWidth
            val cy = box.centerY() / frameHeight
            return cx in 0.08f..0.92f && cy in 0.16f..0.84f
        }
    }
}
