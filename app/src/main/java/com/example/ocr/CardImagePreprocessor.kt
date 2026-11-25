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
 * Pure Kotlin/Android image preprocessing pipeline for Wi-Fi voucher OCR.
 * Generates contrast-stretched, sharpened, and locally adaptive-thresholded images
 * to handle faint gray ink, shadows, wrinkles, glossy glare, and distant cards.
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
     * Extracts an upright grayscale ROI directly from a YUV_420_888 CameraX ImageProxy Y-plane.
     */
    fun extractUprightGrayRoi(
        imageProxy: ImageProxy,
        roiLeftFrac: Float = 0.06f,
        roiTopFrac: Float = 0.18f,
        roiRightFrac: Float = 0.94f,
        roiBottomFrac: Float = 0.82f
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

        val roiW = right - left
        val roiH = bottom - top
        val out = IntArray(roiW * roiH)

        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val limit = buffer.limit()

        var sum = 0L
        var outIdx = 0

        for (uy in top until bottom) {
            for (ux in left until right) {
                val sx: Int
                val sy: Int
                when (rotation) {
                    90 -> {
                        sx = uy
                        sy = srcH - 1 - ux
                    }
                    180 -> {
                        sx = srcW - 1 - ux
                        sy = srcH - 1 - uy
                    }
                    270 -> {
                        sx = srcW - 1 - uy
                        sy = ux
                    }
                    else -> {
                        sx = ux
                        sy = uy
                    }
                }
                val bufIndex = sy * rowStride + sx * pixelStride
                val yVal = if (bufIndex in 0 until limit) {
                    buffer.get(bufIndex).toInt() and 0xFF
                } else {
                    128
                }
                out[outIdx++] = yVal
                sum += yVal
            }
        }

        val avg = if (out.isEmpty()) 128.0 else sum.toDouble() / out.size
        return GrayscalePatch(luma = out, width = roiW, height = roiH, avgLuma = avg)
    }

    fun toRawGrayBitmap(patch: GrayscalePatch, minTargetWidth: Int = 960): Bitmap {
        return lumaToUpscaledBitmap(patch.luma, patch.width, patch.height, minTargetWidth)
    }

    /**
     * Percentile contrast stretching + subtle gamma darkening + 3x3 unsharp mask.
     * Excels on faint gray ink inside dashed boxes (e.g., 6301679399, 6755868974, 5853448690).
     */
    fun toContrastSharpenedBitmap(patch: GrayscalePatch, minTargetWidth: Int = 960): Bitmap {
        val w = patch.width
        val h = patch.height
        val total = w * h
        if (total == 0) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        val stretched = computeContrastStretched(patch.luma)

        // 3x3 unsharp mask: out = clamp(center + 0.70 * (center - localAvg))
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
                sharpened[i] = (c + ((c - avg) * 0.70f).roundToInt()).coerceIn(0, 255)
            }
        }

        return lumaToUpscaledBitmap(sharpened, w, h, minTargetWidth)
    }

    /**
     * Fast O(W*H) integral-image local adaptive thresholding on contrast-normalized luminance.
     * Removes shadows, folded paper gradients, colored backgrounds, and dashed-box noise.
     */
    fun toAdaptiveThresholdBitmap(patch: GrayscalePatch, minTargetWidth: Int = 960): Bitmap {
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
            // ITU-R BT.601 luminance
            val y = (77 * r + 150 * g + 29 * b) ushr 8
            luma[i] = y
            sum += y
        }
        val avg = if (luma.isEmpty()) 128.0 else sum.toDouble() / luma.size
        return GrayscalePatch(luma = luma, width = w, height = h, avgLuma = avg)
    }

    fun decodeOrientedGalleryBitmap(context: Context, uri: Uri, maxDim: Int = 2048): Bitmap? {
        return runCatching {
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
        }.getOrNull()
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
     * Produces primary multi-scale & multi-enhancement passes for a gallery photo:
     * 1. Raw full image (upscaled if small)
     * 2. Center crop (helps distant cards photographed on a surface)
     * 3. Contrast-stretched + sharpened full image
     * 4. Contrast-stretched + sharpened center crop
     * 5. Adaptive-threshold full image
     * 6. Adaptive-threshold center crop
     */
    fun buildPrimaryGalleryPasses(source: Bitmap): List<GalleryPassBitmap> {
        val passes = mutableListOf<GalleryPassBitmap>()
        val fullPatch = bitmapToGrayPatch(source)

        val cropLeft = (source.width * 0.14f).toInt().coerceIn(0, max(0, source.width - 2))
        val cropTop = (source.height * 0.18f).toInt().coerceIn(0, max(0, source.height - 2))
        val cropW = (source.width * 0.72f).toInt().coerceIn(2, source.width - cropLeft)
        val cropH = (source.height * 0.64f).toInt().coerceIn(2, source.height - cropTop)

        val centerBitmap = Bitmap.createBitmap(source, cropLeft, cropTop, cropW, cropH)
        val centerPatch = bitmapToGrayPatch(centerBitmap)

        passes += GalleryPassBitmap(
            bitmap = upscaleIfNeeded(source, minWidth = 1000, copyIfUnchanged = true),
            isCenterCrop = false,
            label = "full_raw"
        )
        passes += GalleryPassBitmap(
            bitmap = upscaleIfNeeded(centerBitmap, minWidth = 1100, copyIfUnchanged = false),
            isCenterCrop = true,
            label = "center_raw"
        )
        passes += GalleryPassBitmap(
            bitmap = toContrastSharpenedBitmap(fullPatch, minTargetWidth = 1000),
            isCenterCrop = false,
            label = "full_contrast"
        )
        passes += GalleryPassBitmap(
            bitmap = toContrastSharpenedBitmap(centerPatch, minTargetWidth = 1100),
            isCenterCrop = true,
            label = "center_contrast"
        )
        passes += GalleryPassBitmap(
            bitmap = toAdaptiveThresholdBitmap(fullPatch, minTargetWidth = 1000),
            isCenterCrop = false,
            label = "full_adaptive"
        )
        passes += GalleryPassBitmap(
            bitmap = toAdaptiveThresholdBitmap(centerPatch, minTargetWidth = 1100),
            isCenterCrop = true,
            label = "center_adaptive"
        )

        return passes
    }

    fun buildRotatedContrastPass(source: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        val patch = bitmapToGrayPatch(rotated)
        if (rotated !== source) rotated.recycle()
        return toContrastSharpenedBitmap(patch, minTargetWidth = 1000)
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
        return if (width < minTargetWidth && width > 0) {
            val scale = (minTargetWidth.toFloat() / width.toFloat()).coerceAtMost(2.5f)
            val newW = (width * scale).roundToInt().coerceAtLeast(width)
            val newH = (height * scale).roundToInt().coerceAtLeast(height)
            Bitmap.createScaledBitmap(raw, newW, newH, true).also {
                if (it !== raw) raw.recycle()
            }
        } else {
            raw
        }
    }

    private fun upscaleIfNeeded(
        bitmap: Bitmap,
        minWidth: Int,
        copyIfUnchanged: Boolean
    ): Bitmap {
        return if (bitmap.width < minWidth && bitmap.width > 0) {
            val scale = (minWidth.toFloat() / bitmap.width.toFloat()).coerceAtMost(2.5f)
            val newW = (bitmap.width * scale).roundToInt().coerceAtLeast(bitmap.width)
            val newH = (bitmap.height * scale).roundToInt().coerceAtLeast(bitmap.height)
            Bitmap.createScaledBitmap(bitmap, newW, newH, true).also {
                if (!copyIfUnchanged && it !== bitmap) bitmap.recycle()
            }
        } else if (copyIfUnchanged) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
    }
}
