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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

class OcrAnalyzer(
    private val analyzeIntervalMs: Long = 90L,
    private val onStateChanged: (OcrScanState) -> Unit
) : ImageAnalysis.Analyzer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val voteEngine = CardCodeVoteEngine()
    private val isProcessing = AtomicBoolean(false)
    private val isLocked = AtomicBoolean(false)
    private val isPausedForGallery = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)
    private val burstRequested = AtomicBoolean(false)
    private val frameCounter = AtomicInteger(0)
    private var lastAnalyzeTimestampMs = 0L

    fun reset() {
        isLocked.set(false)
        isPausedForGallery.set(false)
        burstRequested.set(false)
        frameCounter.set(0)
        voteEngine.reset()
        onStateChanged(OcrScanState.Scanning())
    }

    fun pauseForGallery() {
        isPausedForGallery.set(true)
        burstRequested.set(false)
    }

    fun resumeFromGallery() {
        isPausedForGallery.set(false)
    }

    fun lockExternalCode() {
        isLocked.set(true)
        isPausedForGallery.set(false)
        burstRequested.set(false)
    }

    /**
     * Requests an immediate multi-pass burst analysis on the next camera frame.
     */
    fun requestBurstCapture() {
        isLocked.set(false)
        isPausedForGallery.set(false)
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
        if (isClosed.get() || isLocked.get() || isPausedForGallery.get()) {
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
            patch = CardImagePreprocessor.extractUprightGrayRoi(imageProxy, maxTargetWidth = 800)
            widePatch = if (isBurst) {
                CardImagePreprocessor.extractUprightGrayRoi(
                    imageProxy = imageProxy,
                    roiLeftFrac = 0.02f,
                    roiTopFrac = 0.08f,
                    roiRightFrac = 0.98f,
                    roiBottomFrac = 0.92f,
                    maxTargetWidth = 800
                )
            } else {
                null
            }
        } finally {
            imageProxy.close()
        }

        if (patch == null) {
            isProcessing.set(false)
            return
        }

        if (isBurst) {
            burstRequested.set(false)
            runBurstAnalysis(patch, widePatch)
            return
        }

        val currentFrameIdx = frameCounter.incrementAndGet()
        runFastFirstFrameAnalysis(patch, runAdaptiveAlways = (currentFrameIdx % 2 == 0))
    }

    /**
     * Fast-first live pipeline: runs one crisp contrast-sharpened OCR pass (~25ms),
     * and triggers adaptive-threshold pass only when Pass 0 finds nothing or every 4th frame.
     */
    private fun runFastFirstFrameAnalysis(
        patch: CardImagePreprocessor.GrayscalePatch,
        runAdaptiveAlways: Boolean
    ) {
        var contrastBmp: Bitmap? = null
        try {
            contrastBmp = CardImagePreprocessor.toContrastSharpenedBitmap(patch, minTargetWidth = 720)
            val cw = contrastBmp.width.toFloat()
            val ch = contrastBmp.height.toFloat()

            recognizer.process(InputImage.fromBitmap(contrastBmp, 0))
                .addOnSuccessListener { visionText ->
                    runCatching { contrastBmp.recycle() }
                    if (isClosed.get() || isLocked.get() || isPausedForGallery.get()) {
                        isProcessing.set(false)
                        return@addOnSuccessListener
                    }

                    val obs0 = extractObservations(
                        visionText = visionText,
                        frameWidth = cw,
                        frameHeight = ch,
                        treatAllAsCenter = true,
                        passIndex = 0
                    )

                    val needsAdaptiveFallback = runAdaptiveAlways ||
                        obs0.none { tok -> tok.text.any { it.isDigit() } && tok.text.length >= 6 }

                    if (!needsAdaptiveFallback) {
                        val state = voteEngine.processMultiPassFrame(
                            passes = listOf(obs0),
                            avgLuma = patch.avgLuma,
                            burstCapture = false
                        )
                        if (!isClosed.get() && !isLocked.get() && !isPausedForGallery.get()) {
                            if (state is OcrScanState.Locked) {
                                isLocked.set(true)
                            }
                            onStateChanged(state)
                        }
                        isProcessing.set(false)
                    } else {
                        runAdaptiveFallbackPass(patch, obs0)
                    }
                }
                .addOnFailureListener {
                    runCatching { contrastBmp.recycle() }
                    runAdaptiveFallbackPass(patch, emptyList())
                }
        } catch (_: Throwable) {
            runCatching { contrastBmp?.recycle() }
            isProcessing.set(false)
        }
    }

    private fun runAdaptiveFallbackPass(
        patch: CardImagePreprocessor.GrayscalePatch,
        obs0: List<CandidateObservation>
    ) {
        var adaptiveBmp: Bitmap? = null
        try {
            if (isClosed.get() || isLocked.get() || isPausedForGallery.get()) {
                isProcessing.set(false)
                return
            }
            adaptiveBmp = CardImagePreprocessor.toAdaptiveThresholdBitmap(patch, minTargetWidth = 720)
            val aw = adaptiveBmp.width.toFloat()
            val ah = adaptiveBmp.height.toFloat()

            recognizer.process(InputImage.fromBitmap(adaptiveBmp, 0))
                .addOnSuccessListener { visionText ->
                    if (!isClosed.get() && !isLocked.get() && !isPausedForGallery.get()) {
                        val obs1 = extractObservations(
                            visionText = visionText,
                            frameWidth = aw,
                            frameHeight = ah,
                            treatAllAsCenter = true,
                            passIndex = 1
                        )
                        val passes = if (obs0.isNotEmpty()) listOf(obs0, obs1) else listOf(obs1)
                        val state = voteEngine.processMultiPassFrame(
                            passes = passes,
                            avgLuma = patch.avgLuma,
                            burstCapture = false
                        )
                        if (state is OcrScanState.Locked) {
                            isLocked.set(true)
                        }
                        onStateChanged(state)
                    }
                }
                .addOnFailureListener {
                    if (!isClosed.get() && !isLocked.get() && !isPausedForGallery.get()) {
                        val state = voteEngine.processMultiPassFrame(
                            passes = if (obs0.isNotEmpty()) listOf(obs0) else emptyList(),
                            avgLuma = patch.avgLuma,
                            burstCapture = false
                        )
                        onStateChanged(state)
                    }
                }
                .addOnCompleteListener {
                    runCatching { adaptiveBmp.recycle() }
                    isProcessing.set(false)
                }
        } catch (_: Throwable) {
            runCatching { adaptiveBmp?.recycle() }
            isProcessing.set(false)
        }
    }

    private fun runBurstAnalysis(
        patch: CardImagePreprocessor.GrayscalePatch,
        widePatch: CardImagePreprocessor.GrayscalePatch?
    ) {
        val bitmapsToRecycle = mutableListOf<Bitmap>()
        try {
            val contrastBmp = CardImagePreprocessor.toContrastSharpenedBitmap(patch, minTargetWidth = 800)
            bitmapsToRecycle += contrastBmp
            val adaptiveBmp = CardImagePreprocessor.toAdaptiveThresholdBitmap(patch, minTargetWidth = 800)
            bitmapsToRecycle += adaptiveBmp
            val rawBmp = CardImagePreprocessor.toRawGrayBitmap(patch, minTargetWidth = 800)
            bitmapsToRecycle += rawBmp

            val taskList = mutableListOf(
                recognizer.process(InputImage.fromBitmap(contrastBmp, 0)),
                recognizer.process(InputImage.fromBitmap(adaptiveBmp, 0)),
                recognizer.process(InputImage.fromBitmap(rawBmp, 0))
            )
            val passDims = mutableListOf(
                contrastBmp.width.toFloat() to contrastBmp.height.toFloat(),
                adaptiveBmp.width.toFloat() to adaptiveBmp.height.toFloat(),
                rawBmp.width.toFloat() to rawBmp.height.toFloat()
            )

            if (widePatch != null) {
                val wideContrast = CardImagePreprocessor.toContrastSharpenedBitmap(widePatch, minTargetWidth = 800)
                bitmapsToRecycle += wideContrast
                taskList += recognizer.process(InputImage.fromBitmap(wideContrast, 0))
                passDims += wideContrast.width.toFloat() to wideContrast.height.toFloat()
            }

            Tasks.whenAllComplete(taskList)
                .addOnCompleteListener {
                    try {
                        if (!isClosed.get() && !isLocked.get() && !isPausedForGallery.get()) {
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
                                burstCapture = true
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
        }
    }

    companion object {
        suspend fun scanImageUri(context: Context, uri: Uri): OcrScanState = withContext(Dispatchers.Default) {
            val sourceBitmap = CardImagePreprocessor.decodeOrientedGalleryBitmap(context, uri)
                ?: return@withContext OcrScanState.Error("تعذر قراءة الصورة المختارة")

            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val voteEngine = CardCodeVoteEngine()

            try {
                val allObservations = mutableListOf<List<CandidateObservation>>()

                // Stage 1: Fast 2-pass scan (center contrast + full raw) for instant recognition
                val fastPasses = CardImagePreprocessor.buildFastGalleryPasses(sourceBitmap)
                try {
                    fastPasses.forEachIndexed { idx, pass ->
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
                    fastPasses.forEach { pass -> runCatching { pass.bitmap.recycle() } }
                }

                val fastResult = voteEngine.selectMultiPassImageCandidate(
                    passes = allObservations,
                    sourceLabel = "المعرض"
                )
                if (fastResult is OcrScanState.Locked && fastResult.confidence >= 0.80f) {
                    return@withContext fastResult
                }

                // Stage 2: Deep enhancement passes for faint, shadowed, or cluttered gallery photos
                val deepPasses = CardImagePreprocessor.buildDeepGalleryPasses(sourceBitmap)
                try {
                    deepPasses.forEachIndexed { idx, pass ->
                        val visionText = awaitTextRecognition(recognizer, pass.bitmap)
                        if (visionText != null) {
                            allObservations += extractObservations(
                                visionText = visionText,
                                frameWidth = pass.bitmap.width.toFloat(),
                                frameHeight = pass.bitmap.height.toFloat(),
                                treatAllAsCenter = pass.isCenterCrop,
                                passIndex = 2 + idx
                            )
                        }
                    }
                } finally {
                    deepPasses.forEach { pass -> runCatching { pass.bitmap.recycle() } }
                }

                var result = voteEngine.selectMultiPassImageCandidate(
                    passes = allObservations,
                    sourceLabel = "المعرض"
                )

                // Stage 3: Fallback rotations if card photo was saved sideways or upside down
                if (result is OcrScanState.Error) {
                    val rotatedObservations = mutableListOf<List<CandidateObservation>>()
                    val angles = listOf(90f, 270f, 180f)
                    for ((idx, angle) in angles.withIndex()) {
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
                        val earlyRot = voteEngine.selectMultiPassImageCandidate(
                            passes = rotatedObservations,
                            sourceLabel = "المعرض"
                        )
                        if (earlyRot is OcrScanState.Locked) {
                            result = earlyRot
                            break
                        }
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

        private val embeddedTokenRegex = Regex("""[A-Za-z0-9]{6,14}""")
        private val spacedVoucherRegex = Regex("""(?:^|[:\s-])([A-Za-z0-9]{2,6}(?:\s+[A-Za-z0-9]{2,6}){1,2})(?:\s|$)""")

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

                        if (line.elements.size in 2..3 && lineText.all { it.isLetterOrDigit() || it.isWhitespace() }) {
                            val collapsed = lineText.replace("\\s+".toRegex(), "")
                            if (collapsed.length in 6..14) {
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
                        } else {
                            // Also recover space-split voucher codes on labeled lines (e.g. "PIN: 6301 679399")
                            for (match in spacedVoucherRegex.findAll(lineText)) {
                                val collapsed = match.groupValues[1].replace("\\s+".toRegex(), "")
                                if (collapsed.length in 6..14 && collapsed.any { it.isDigit() }) {
                                    list.add(
                                        CandidateObservation(
                                            text = collapsed,
                                            normalizedHeight = lineBox.height() / safeH,
                                            inCenterRoi = lineInRoi,
                                            mlKitConfidence = (lineConf - 0.04f).coerceAtLeast(0.5f),
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
