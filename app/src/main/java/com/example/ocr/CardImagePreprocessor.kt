package com.example.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.camera.core.ImageProxy
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Fast pure Kotlin/Android image preprocessing pipeline for Wi-Fi voucher OCR.
 * Generates contrast-stretched, sharpened, and locally adaptive-thresholded images
 * with branch-free rotation extraction and staged gallery passes for low latency.
 */
object CardImagePreprocessor {

    data class GrayscalePatch(
        val luma: IntArray,
        val width: Int,
        val height: Int,
        val avgLuma: Double
    )

    data class GalleryPassBitmap(
        val bitmap: Bitmap,
        val isCenterCrop: Boolean,
        val label: String
    )

    /**
     * Extracts an upright grayscale ROI directly from a YUV_420_888 CameraX ImageProxy Y-plane
     * using branch-free rotation loops and optional 2x downsampling if sensor output is oversized.
     */
    fun extractUprightGrayRoi(
        imageProxy: ImageProxy,
        roiLeftFrac: Float = 0.08f,
        roiTopFrac: Float = 0.22f,
        roiRightFrac: Float = 0.92f,
        roiBottomFrac: Float = 0.78f,
        maxTargetWidth: Int = 800
    ): GrayscalePatch? {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val srcW = imageProxy.width
        val srcH = imageProxy.height
        if (srcW <= 0 || srcH <= 0) return null

        val rotation = ((imageProxy.imageInfo.rotationDegrees % 360) + 360) % 360
        val uprightW = if (rotation == 90 || rotation == 270) srcH else srcW
        val uprightH = if (rotation == 90 || rotation == 270) srcW else srcH

        val left = (uprightW * roiLeftFrac).toInt().coerceIn(0, uprightW - 2)
        val top = (uprightH * roiTopFrac).toInt().coerceIn(0, uprightH - 2)
        val right = (uprightW * roiRightFrac).toInt().coerceIn(left + 1, uprightW)
        val bottom = (uprightH * roiBottomFrac).toInt().coerceIn(top + 1, uprightH)

        val rawRoiW = right - left
        val step = if (rawRoiW > (maxTargetWidth * 1.6f).toInt()) 2 else 1
        val outW = ((right - left) + step - 1) / step
        val outH = ((bottom - top) + step - 1) / step
        if (outW <= 0 || outH <= 0) return null

        val out = IntArray(outW * outH)
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val limit = buffer.limit()

        var sum = 0L
        var outIdx = 0

        when (rotation) {
            90 -> {
                var uy = top
                while (uy < bottom) {
                    var ux = left
                    while (ux < right) {
                        val sy = srcH - 1 - ux
                        val bufIndex = sy * rowStride + uy * pixelStride
                        val yVal = if (bufIndex in 0 until limit) buffer.get(bufIndex).toInt() and 0xFF else 128
                        out[outIdx++] = yVal
                        sum += yVal
                        ux += step
                    }
                    uy += step
                }
            }
            180 -> {
                var uy = top
                while (uy < bottom) {
                    val sy = srcH - 1 - uy
                    val rowBase = sy * rowStride
                    var ux = left
                    while (ux < right) {
                        val sx = srcW - 1 - ux
                        val bufIndex = rowBase + sx * pixelStride
                        val yVal = if (bufIndex in 0 until limit) buffer.get(bufIndex).toInt() and 0xFF else 128
                        out[outIdx++] = yVal
                        sum += yVal
                        ux += step
                    }
                    uy += step
                }
            }
            270 -> {
                var uy = top
                while (uy < bottom) {
                    val sx = srcW - 1 - uy
                    val colOffset = sx * pixelStride
                    var ux = left
                    while (ux < right) {
                        val bufIndex = ux * rowStride + colOffset
                        val yVal = if (bufIndex in 0 until limit) buffer.get(bufIndex).toInt() and 0xFF else 128
                        out[outIdx++] = yVal
                        sum += yVal
                        ux += step
                    }
                    uy += step
                }
            }
            else -> {
                var uy = top
                while (uy < bottom) {
                    val rowBase = uy * rowStride
                    var ux = left
                    while (ux < right) {
                        val bufIndex = rowBase + ux * pixelStride
                        val yVal = if (bufIndex in 0 until limit) buffer.get(bufIndex).toInt() and 0xFF else 128
                        out[outIdx++] = yVal
                        sum += yVal
                        ux += step
                    }
                    uy += step
                }
            }
        }

        val avg = if (outIdx == 0) 128.0 else sum.toDouble() / outIdx
        return GrayscalePatch(luma = out, width = outW, height = outH, avgLuma = avg)
    }

    fun toRawGrayBitmap(patch: GrayscalePatch, minTargetWidth: Int = 720): Bitmap {
        return lumaToUpscaledBitmap(patch.luma, patch.width, patch.height, minTargetWidth)
    }

    /**
     * Percentile contrast stretching + subtle gamma darkening + 3x3 unsharp mask.
     */
    fun toContrastSharpenedBitmap(patch: GrayscalePatch, minTargetWidth: Int = 720): Bitmap {
        val w = patch.width
        val h = patch.height
        val total = w * h
        if (total == 0) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        val stretched = computeContrastStretched(patch.luma)
        val sharpened = IntArray(total)

        for (y in 0 until h) {
            val rowOffset = y * w
            if (y == 0 || y == h - 1) {
                System.arraycopy(stretched, rowOffset, sharpened, rowOffset, w)
                continue
            }
            sharpened[rowOffset] = stretched[rowOffset]
            sharpened[rowOffset + w - 1] = stretched[rowOffset + w - 1]
            for (x in 1 until w - 1) {
                val i = rowOffset + x
                val c = stretched[i]
                val avg = (
                    stretched[i - w - 1] + stretched[i - w] + stretched[i - w + 1] +
                        stretched[i - 1] + c + stretched[i + 1] +
                        stretched[i + w - 1] + stretched[i + w] + stretched[i + w + 1]
                    ) / 9
                sharpened[i] = (c + ((c - avg) * 7) / 10).coerceIn(0, 255)
            }
        }

        return lumaToUpscaledBitmap(sharpened, w, h, minTargetWidth)
    }

    /**
     * Fast O(W*H) integral-image local adaptive thresholding on contrast-normalized luminance.
     */
    fun toAdaptiveThresholdBitmap(patch: GrayscalePatch, minTargetWidth: Int = 720): Bitmap {
        val w = patch.width
        val h = patch.height
        val total = w * h
        if (total == 0) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        val src = computeContrastStretched(patch.luma)
        val iw = w + 1
        val integral = LongArray(iw * (h + 1))

        for (y in 1..h) {
            var rowSum = 0L
            val srcRow = (y - 1) * w
            val intRow = y * iw
            val prevRow = (y - 1) * iw
            for (x in 1..w) {
                rowSum += src[srcRow + x - 1].coerceIn(0, 255)
                integral[intRow + x] = integral[prevRow + x] + rowSum
            }
        }

        val radius = max(12, min(w, h) / 26)
        val binary = IntArray(total)

        for (y in 0 until h) {
            val y1 = max(0, y - radius)
            val y2 = min(h - 1, y + radius)
            val rowOff = y * w
            val r1Off = y1 * iw
            val r2Off = (y2 + 1) * iw
            for (x in 0 until w) {
                val x1 = max(0, x - radius)
                val x2 = min(w - 1, x + radius)
                val area = (x2 - x1 + 1) * (y2 - y1 + 1)
                val sum = integral[r2Off + x2 + 1] -
                    integral[r1Off + x2 + 1] -
                    integral[r2Off + x1] +
                    integral[r1Off + x1]
                val localMean = (sum / area).toInt()
                val delta = max(8, (localMean * 8) / 100)
                val px = src[rowOff + x]
                binary[rowOff + x] = if (px < localMean - delta) 0 else 255
            }
        }

        return lumaToUpscaledBitmap(binary, w, h, minTargetWidth)
    }

    private fun computeContrastStretched(src: IntArray): IntArray {
        val total = src.size
        val hist = IntArray(256)
        for (v in src) {
            hist[v.coerceIn(0, 255)]++
        }

        val lowTarget = (total * 0.015f).toInt()
        val highTarget = (total * 0.985f).toInt()
        var cum = 0
        var lowVal = 0
        var highVal = 255
        var foundLow = false
        for (i in 0..255) {
            cum += hist[i]
            if (!foundLow && cum >= lowTarget) {
                lowVal = i
                foundLow = true
            }
            if (cum >= highTarget) {
                highVal = i
                break
            }
        }
        if (highVal <= lowVal + 12) {
            lowVal = 0
            highVal = 255
        }

        val lut = IntArray(256)
        val span = (highVal - lowVal).coerceAtLeast(1).toFloat()
        for (i in 0..255) {
            val norm = ((i - lowVal) / span).coerceIn(0f, 1f)
            val curved = norm.toDouble().pow(1.18).toFloat()
            lut[i] = (curved * 255f).roundToInt().coerceIn(0, 255)
        }

        val stretched = IntArray(total)
        for (idx in 0 until total) {
            stretched[idx] = lut[src[idx].coerceIn(0, 255)]
        }
        return stretched
    }

    fun bitmapToGrayPatch(bitmap: Bitmap): GrayscalePatch {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val luma = IntArray(pixels.size)
        var sum = 0L
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            val y = (77 * r + 150 * g + 29 * b) ushr 8
            luma[i] = y
            sum += y
        }
        val avg = if (luma.isEmpty()) 128.0 else sum.toDouble() / luma.size
        return GrayscalePatch(luma = luma, width = w, height = h, avgLuma = avg)
    }

    /**
     * Resizes any bitmap via Android Canvas so its width never exceeds [maxWidth] (default 800px),
     * ensuring sub-second OCR latency on both camera captures and high-res gallery photos.
     */
    fun resizeToMaxWidthViaCanvas(bitmap: Bitmap, maxWidth: Int = 800): Bitmap {
        if (maxWidth <= 0 || bitmap.width <= maxWidth || bitmap.width <= 0 || bitmap.height <= 0) {
            return bitmap
        }
        val scale = maxWidth.toFloat() / bitmap.width.toFloat()
        val targetW = maxWidth
        val targetH = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val output = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        val paint = android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG
        )
        canvas.drawBitmap(
            bitmap,
            android.graphics.Rect(0, 0, bitmap.width, bitmap.height),
            android.graphics.Rect(0, 0, targetW, targetH),
            paint
        )
        return output
    }

    fun decodeOrientedGalleryBitmap(context: Context, uri: Uri, maxDim: Int = 800): Bitmap? {
        val decoded = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    val longest = max(w, h)
                    if (longest > maxDim) {
                        val scale = maxDim.toFloat() / longest.toFloat()
                        decoder.setTargetSize(
                            (w * scale).roundToInt().coerceAtLeast(1),
                            (h * scale).roundToInt().coerceAtLeast(1)
                        )
                    }
                }
            } else {
                decodeLegacyOrientedBitmap(context, uri, maxDim)
            }
        }.getOrNull() ?: return null

        val canvasScaled = resizeToMaxWidthViaCanvas(decoded, maxWidth = maxDim)
        if (canvasScaled !== decoded) {
            decoded.recycle()
        }
        return canvasScaled
    }

    private fun decodeLegacyOrientedBitmap(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        if (rawW <= 0 || rawH <= 0) return null

        var sample = 1
        while (max(rawW / sample, rawH / sample) > maxDim * 2) {
            sample *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: return null

        val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
        }

        val rotated = if (!matrix.isIdentity) {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }

        val longest = max(rotated.width, rotated.height)
        return if (longest > maxDim) {
            val scale = maxDim.toFloat() / longest.toFloat()
            val nw = (rotated.width * scale).roundToInt().coerceAtLeast(1)
            val nh = (rotated.height * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(rotated, nw, nh, true).also {
                if (it !== rotated) rotated.recycle()
            }
        } else {
            rotated
        }
    }

    /**
     * Fast stage-1 gallery passes (center contrast+sharpen + full Canvas-resized 800px raw)
     * for sub-second recognition on clear cards.
     */
    fun buildFastGalleryPasses(source: Bitmap): List<GalleryPassBitmap> {
        val normalized = resizeToMaxWidthViaCanvas(source, maxWidth = 800)
        val centerBitmap = extractCenterCropBitmap(normalized)
        val centerPatch = bitmapToGrayPatch(centerBitmap)
        if (centerBitmap !== normalized) centerBitmap.recycle()

        val fullCopy = if (normalized !== source) {
            normalized
        } else {
            normalized.copy(Bitmap.Config.ARGB_8888, false)
        }

        return listOf(
            GalleryPassBitmap(
                bitmap = toContrastSharpenedBitmap(centerPatch, minTargetWidth = 720),
                isCenterCrop = true,
                label = "center_contrast"
            ),
            GalleryPassBitmap(
                bitmap = fullCopy,
                isCenterCrop = false,
                label = "full_raw"
            )
        )
    }

    /**
     * Deep stage-2 gallery passes for faint, scratched, shadowed, or cluttered photos,
     * capped at 800px width for fast ML Kit inference.
     */
    fun buildDeepGalleryPasses(source: Bitmap): List<GalleryPassBitmap> {
        val normalized = resizeToMaxWidthViaCanvas(source, maxWidth = 800)
        val fullPatch = bitmapToGrayPatch(normalized)
        val centerBitmap = extractCenterCropBitmap(normalized)
        val centerPatch = bitmapToGrayPatch(centerBitmap)
        if (normalized !== source) normalized.recycle()

        return listOf(
            GalleryPassBitmap(
                bitmap = upscaleIfNeeded(centerBitmap, minWidth = 720, copyIfUnchanged = false, maxWidth = 800),
                isCenterCrop = true,
                label = "center_raw"
            ),
            GalleryPassBitmap(
                bitmap = toContrastSharpenedBitmap(fullPatch, minTargetWidth = 720),
                isCenterCrop = false,
                label = "full_contrast"
            ),
            GalleryPassBitmap(
                bitmap = toAdaptiveThresholdBitmap(fullPatch, minTargetWidth = 720),
                isCenterCrop = false,
                label = "full_adaptive"
            ),
            GalleryPassBitmap(
                bitmap = toAdaptiveThresholdBitmap(centerPatch, minTargetWidth = 760),
                isCenterCrop = true,
                label = "center_adaptive"
            )
        )
    }

    /**
     * Full 6-pass list kept for compatibility & unit tests.
     */
    fun buildPrimaryGalleryPasses(source: Bitmap): List<GalleryPassBitmap> {
        return buildFastGalleryPasses(source) + buildDeepGalleryPasses(source)
    }

    private fun extractCenterCropBitmap(source: Bitmap): Bitmap {
        val cropLeft = (source.width * 0.10f).toInt().coerceIn(0, max(0, source.width - 2))
        val cropTop = (source.height * 0.16f).toInt().coerceIn(0, max(0, source.height - 2))
        val cropW = (source.width * 0.80f).toInt().coerceIn(2, source.width - cropLeft)
        val cropH = (source.height * 0.68f).toInt().coerceIn(2, source.height - cropTop)
        return Bitmap.createBitmap(source, cropLeft, cropTop, cropW, cropH)
    }

    fun buildRotatedContrastPass(source: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        val capped = resizeToMaxWidthViaCanvas(rotated, maxWidth = 800)
        if (capped !== rotated) rotated.recycle()
        val patch = bitmapToGrayPatch(capped)
        if (capped !== source) capped.recycle()
        return toContrastSharpenedBitmap(patch, minTargetWidth = 720)
    }

    private fun lumaToUpscaledBitmap(
        luma: IntArray,
        width: Int,
        height: Int,
        minTargetWidth: Int
    ): Bitmap {
        val argb = IntArray(width * height)
        for (i in argb.indices) {
            val v = luma[i].coerceIn(0, 255)
            argb[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val raw = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        val effectiveMax = max(800, minTargetWidth)
        return if (width < minTargetWidth && width > 0) {
            val scale = (minTargetWidth.toFloat() / width.toFloat()).coerceAtMost(2.5f)
            val newW = (width * scale).roundToInt().coerceAtLeast(width).coerceAtMost(effectiveMax)
            val newH = (height * scale).roundToInt().coerceAtLeast(height)
            Bitmap.createScaledBitmap(raw, newW, newH, true).also {
                if (it !== raw) raw.recycle()
            }
        } else if (width > effectiveMax) {
            resizeToMaxWidthViaCanvas(raw, maxWidth = effectiveMax).also {
                if (it !== raw) raw.recycle()
            }
        } else {
            raw
        }
    }

    private fun upscaleIfNeeded(
        bitmap: Bitmap,
        minWidth: Int,
        copyIfUnchanged: Boolean,
        maxWidth: Int = 800
    ): Bitmap {
        val effectiveMax = max(maxWidth, minWidth)
        return if (bitmap.width < minWidth && bitmap.width > 0) {
            val scale = (minWidth.toFloat() / bitmap.width.toFloat()).coerceAtMost(2.5f)
            val newW = (bitmap.width * scale).roundToInt().coerceAtLeast(bitmap.width).coerceAtMost(effectiveMax)
            val newH = (bitmap.height * scale).roundToInt().coerceAtLeast(bitmap.height)
            Bitmap.createScaledBitmap(bitmap, newW, newH, true).also {
                if (!copyIfUnchanged && it !== bitmap) bitmap.recycle()
            }
        } else if (bitmap.width > effectiveMax) {
            resizeToMaxWidthViaCanvas(bitmap, maxWidth = effectiveMax).also {
                if (!copyIfUnchanged && it !== bitmap) bitmap.recycle()
            }
        } else if (copyIfUnchanged) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
    }
}
