package com.example

import android.graphics.Bitmap
import android.graphics.Color
import com.example.ocr.CardImagePreprocessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardImagePreprocessorTest {

    @Test
    fun contrastStretchAndAdaptiveThresholdProduceCrispUpscaledBitmaps() {
        val width = 400
        val height = 200
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bg = Color.rgb(245, 225, 210)
        val faintInk = Color.rgb(195, 190, 185)

        val pixels = IntArray(width * height) { bg }
        // Draw deterministic faint gray strokes (mimicking faint digits on pale card)
        for (digit in 0 until 10) {
            val xStart = 60 + digit * 28
            for (y in 80 until 125) {
                for (x in xStart until (xStart + 6)) {
                    pixels[y * width + x] = faintInk
                }
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        val patch = CardImagePreprocessor.bitmapToGrayPatch(bitmap)
        assertEquals(width, patch.width)
        assertEquals(height, patch.height)

        val contrastBmp = CardImagePreprocessor.toContrastSharpenedBitmap(patch, minTargetWidth = 960)
        val adaptiveBmp = CardImagePreprocessor.toAdaptiveThresholdBitmap(patch, minTargetWidth = 960)
        val galleryPasses = CardImagePreprocessor.buildPrimaryGalleryPasses(bitmap)

        assertTrue(contrastBmp.width >= 960)
        assertTrue(adaptiveBmp.width >= 960)
        assertEquals(6, galleryPasses.size)

        var darkPixels = 0
        val samplePixels = IntArray(adaptiveBmp.width * adaptiveBmp.height)
        adaptiveBmp.getPixels(samplePixels, 0, adaptiveBmp.width, 0, 0, adaptiveBmp.width, adaptiveBmp.height)
        for (px in samplePixels) {
            if ((px and 0xFF) < 64) darkPixels++
        }
        assertTrue("Expected dark foreground pixels in adaptive threshold output", darkPixels > 50)

        contrastBmp.recycle()
        adaptiveBmp.recycle()
        galleryPasses.forEach { it.bitmap.recycle() }
        bitmap.recycle()
    }

    @Test
    fun canvasResizeCapsOversizedGalleryBitmapTo800px() {
        val large = Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888)
        val resized = CardImagePreprocessor.resizeToMaxWidthViaCanvas(large, maxWidth = 800)

        assertEquals(800, resized.width)
        assertEquals(400, resized.height)

        val fastPasses = CardImagePreprocessor.buildFastGalleryPasses(large)
        assertEquals(2, fastPasses.size)
        fastPasses.forEach { pass ->
            assertTrue("Expected pass ${pass.label} width <= 800, got ${pass.bitmap.width}", pass.bitmap.width <= 800)
            pass.bitmap.recycle()
        }

        if (resized !== large) resized.recycle()
        large.recycle()
    }
}
