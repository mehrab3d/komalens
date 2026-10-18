package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.SourceLanguage
import com.example.ocr.AutoScanDebouncer
import com.example.ocr.MangaOcrPipeline
import com.example.ocr.OfflineModelState
import com.example.overlay.OverlayStateHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("KomaLens", appName)
    }

    @Test
    fun `containsCjkCharacters filters out status bar clock and battery text`() {
        assertFalse(MangaOcrPipeline.containsCjkCharacters("07:42 AM 98% 5G LTE"))
        assertTrue(MangaOcrPipeline.containsCjkCharacters("일어나라 그림자 군단이여"))
        assertTrue(MangaOcrPipeline.containsCjkCharacters("領域展開"))
        assertTrue(MangaOcrPipeline.containsCjkCharacters("万剑归宗"))
    }

    @Test
    fun `sampleBubbleColors matches light and dark comic speech bubbles`() {
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()

        // Top half: light cream bubble (#FAF8F2)
        paint.color = Color.parseColor("#FAF8F2")
        canvas.drawRect(0f, 0f, 200f, 100f, paint)

        // Bottom half: dark monarch bubble (#0E1528)
        paint.color = Color.parseColor("#0E1528")
        canvas.drawRect(0f, 100f, 200f, 200f, paint)

        val (lightBg, lightTxt) = MangaOcrPipeline.sampleBubbleColors(bmp, 0.1f, 0.1f, 0.9f, 0.4f)
        val (darkBg, darkTxt) = MangaOcrPipeline.sampleBubbleColors(bmp, 0.1f, 0.6f, 0.9f, 0.9f)

        val lightBgLum = Color.red(lightBg) + Color.green(lightBg) + Color.blue(lightBg)
        val lightTxtLum = Color.red(lightTxt) + Color.green(lightTxt) + Color.blue(lightTxt)
        assertTrue("Light bubble should have bright background and dark text", lightBgLum > lightTxtLum)
        assertEquals("Light bubble background must be 100% opaque", 255, Color.alpha(lightBg))

        val darkBgLum = Color.red(darkBg) + Color.green(darkBg) + Color.blue(darkBg)
        val darkTxtLum = Color.red(darkTxt) + Color.green(darkTxt) + Color.blue(darkTxt)
        assertTrue("Dark bubble should have dark background and bright text", darkTxtLum > darkBgLum)
        assertEquals("Dark bubble background must be 100% opaque", 255, Color.alpha(darkBg))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `autoScanDebouncer waits 500ms and cancels intermediate scroll triggers`() = runTest {
        val debouncer = AutoScanDebouncer(scope = this, debounceDelayMs = 500L)
        assertEquals(500L, debouncer.debounceDelayMs)

        var triggerCount = 0

        // Simulate 4 rapid scroll frames 150ms apart
        repeat(4) {
            debouncer.onScrollOrFrameChanged {
                triggerCount++
            }
            advanceTimeBy(150L)
            assertEquals("Should not trigger before 500ms quiet period", 0, triggerCount)
        }

        // Wait another 340ms (total 490ms since last scroll event) -> still 0
        advanceTimeBy(340L)
        assertEquals(0, triggerCount)

        // Advance past the 500ms mark -> triggers exactly once
        advanceTimeBy(20L)
        assertEquals(1, triggerCount)
    }

    @Test
    fun `overlayStateHolder tracks source language switching and offline model status`() {
        OverlayStateHolder.setLanguage(SourceLanguage.JA)
        assertEquals(SourceLanguage.JA, OverlayStateHolder.activeLanguage.value)

        OverlayStateHolder.updateOfflineModelState(SourceLanguage.JA, OfflineModelState.READY)
        assertEquals(OfflineModelState.READY, OverlayStateHolder.offlineModelStates.value[SourceLanguage.JA])

        OverlayStateHolder.setLanguage(SourceLanguage.KO)
        assertEquals(SourceLanguage.KO, OverlayStateHolder.activeLanguage.value)
    }
}
