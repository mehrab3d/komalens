package com.example.ocr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.example.data.model.DetectedBubble
import com.example.data.model.GlossaryEntry
import com.example.data.model.SourceLanguage
import com.example.network.GeminiTranslationEngine
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class OfflineModelState {
    NOT_DOWNLOADED,
    DOWNLOADING,
    READY,
    FAILED
}

/**
 * Coroutine-based debouncer for continuous auto-scan while scrolling webtoons.
 * Enforces a default 500ms quiet period after the last scroll movement before
 * launching a new OCR and translation cycle, preventing redundant API calls and UI flickering.
 */
class AutoScanDebouncer(
    private val scope: CoroutineScope,
    val debounceDelayMs: Long = MangaOcrPipeline.AUTO_SCAN_DEBOUNCE_DELAY_MS
) {
    private var debounceJob: Job? = null

    fun onScrollOrFrameChanged(action: suspend () -> Unit) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceDelayMs)
            action()
        }
    }

    fun cancel() {
        debounceJob?.cancel()
        debounceJob = null
    }

    val isPending: Boolean
        get() = debounceJob?.isActive == true
}

object MangaOcrPipeline {

    const val AUTO_SCAN_DEBOUNCE_DELAY_MS: Long = 500L

    private val japaneseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    private val koreanRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    private val chineseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    private val translators = ConcurrentHashMap<SourceLanguage, Translator>()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    // Fast in-memory cache so continuous auto-scan while scrolling webtoons never makes redundant API calls
    private val translationCache = ConcurrentHashMap<String, String>()

    // Last completed batch signature to avoid re-running Gemini enrichment on identical bubble sets
    @Volatile
    private var lastBatchKey: String? = null

    @Volatile
    private var lastBatchResult: List<DetectedBubble> = emptyList()

    private fun recognizerFor(language: SourceLanguage): TextRecognizer = when (language) {
        SourceLanguage.JA -> japaneseRecognizer
        SourceLanguage.KO -> koreanRecognizer
        SourceLanguage.ZH -> chineseRecognizer
    }

    private fun translatorFor(language: SourceLanguage): Translator {
        return translators.getOrPut(language) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(mlKitLangCode(language))
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
            Translation.getClient(options)
        }
    }

    private fun googleLangCode(language: SourceLanguage): String = when (language) {
        SourceLanguage.JA -> "ja"
        SourceLanguage.KO -> "ko"
        SourceLanguage.ZH -> "zh-CN"
    }

    fun mlKitLangCode(language: SourceLanguage): String = when (language) {
        SourceLanguage.JA -> TranslateLanguage.JAPANESE
        SourceLanguage.KO -> TranslateLanguage.KOREAN
        SourceLanguage.ZH -> TranslateLanguage.CHINESE
    }

    /**
     * Checks if the ML Kit on-device translation model for [language] -> English is already downloaded.
     */
    suspend fun isOfflineModelDownloaded(language: SourceLanguage): Boolean = withContext(Dispatchers.IO) {
        try {
            val model = TranslateRemoteModel.Builder(mlKitLangCode(language)).build()
            withTimeoutOrNull(4000L) {
                RemoteModelManager.getInstance().isModelDownloaded(model).await()
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Queries the current download status of all supported source languages (Korean, Japanese, Chinese).
     */
    suspend fun checkAllOfflineModels(): Map<SourceLanguage, OfflineModelState> = coroutineScope {
        SourceLanguage.entries.map { lang ->
            async {
                val downloaded = isOfflineModelDownloaded(lang)
                lang to if (downloaded) OfflineModelState.READY else OfflineModelState.NOT_DOWNLOADED
            }
        }.awaitAll().toMap()
    }

    /**
     * Pre-downloads the on-device ML Kit translation model for [language] -> English so offline
     * translation works immediately without network delay.
     */
    suspend fun preDownloadOfflineModel(language: SourceLanguage): Boolean = withContext(Dispatchers.IO) {
        try {
            val translator = translatorFor(language)
            val conditions = DownloadConditions.Builder().build()
            withTimeoutOrNull(60000L) {
                translator.downloadModelIfNeeded(conditions).await()
                true
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks whether the OCR text contains actual Japanese (Hiragana, Katakana, Kanji),
     * Korean (Hangul), or Chinese (Hanzi) characters.
     * Filters out status bar clocks ("07:37"), battery ("95%"), network ("5G", "LTE"),
     * page numbers ("12/45"), and English UI buttons.
     */
    fun containsCjkCharacters(text: String): Boolean {
        for (ch in text) {
            val code = ch.code
            val isHiragana = code in 0x3040..0x309F
            val isKatakana = code in 0x30A0..0x30FF || code in 0x31F0..0x31FF
            val isCjkIdeograph = code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF
            val isHangul = code in 0xAC00..0xD7AF || code in 0x1100..0x11FF || code in 0x3130..0x318F
            if (isHiragana || isKatakana || isCjkIdeograph || isHangul) {
                return true
            }
        }
        return false
    }

    /**
     * Samples the comic bitmap inside the detected bubble's normalized bounds to extract
     * a 100% opaque speech bubble background color and contrasting text color.
     */
    fun sampleBubbleColors(
        bitmap: Bitmap,
        normLeft: Float,
        normTop: Float,
        normRight: Float,
        normBottom: Float
    ): Pair<Int, Int> {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 4 || h <= 4) {
            return Color.argb(255, 250, 248, 242) to Color.argb(255, 13, 15, 20)
        }

        val leftPx = (normLeft * w).roundToInt().coerceIn(0, w - 2)
        val topPx = (normTop * h).roundToInt().coerceIn(0, h - 2)
        val rightPx = (normRight * w).roundToInt().coerceIn(leftPx + 1, w - 1)
        val bottomPx = (normBottom * h).roundToInt().coerceIn(topPx + 1, h - 1)

        data class PixelSample(val r: Int, val g: Int, val b: Int, val lum: Float, val isEdge: Boolean)

        val samples = ArrayList<PixelSample>(160)
        val stepsX = 11
        val stepsY = 11
        val spanW = (rightPx - leftPx).coerceAtLeast(1)
        val spanH = (bottomPx - topPx).coerceAtLeast(1)

        for (ix in 0..stepsX) {
            val px = (leftPx + (spanW * ix) / stepsX).coerceIn(0, w - 1)
            for (iy in 0..stepsY) {
                val py = (topPx + (spanH * iy) / stepsY).coerceIn(0, h - 1)
                val c = bitmap.getPixel(px, py)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                val isEdge = ix == 0 || ix == stepsX || iy == 0 || iy == stepsY
                samples.add(PixelSample(r, g, b, lum, isEdge))
            }
        }

        if (samples.isEmpty()) {
            return Color.argb(255, 250, 248, 242) to Color.argb(255, 13, 15, 20)
        }

        val sortedByLum = samples.sortedBy { it.lum }
        val edgeSamples = samples.filter { it.isEdge }.sortedBy { it.lum }
        val medianLum = if (edgeSamples.isNotEmpty()) {
            edgeSamples[edgeSamples.size / 2].lum
        } else {
            sortedByLum[sortedByLum.size / 2].lum
        }

        val isLightBubble = medianLum >= 128f

        // Sample the purest 30% background pixels (free of anti-aliased text strokes) and force 255 alpha (100% opaque)
        val bgSlice = if (isLightBubble) {
            sortedByLum.subList((sortedByLum.size * 0.70f).toInt().coerceAtMost(sortedByLum.size - 1), sortedByLum.size)
        } else {
            sortedByLum.subList(0, (sortedByLum.size * 0.30f).toInt().coerceAtLeast(1))
        }

        val bgR = bgSlice.map { it.r }.average().roundToInt().coerceIn(0, 255)
        val bgG = bgSlice.map { it.g }.average().roundToInt().coerceIn(0, 255)
        val bgB = bgSlice.map { it.b }.average().roundToInt().coerceIn(0, 255)
        val bgLum = 0.299f * bgR + 0.587f * bgG + 0.114f * bgB
        val bgColor = Color.argb(255, bgR, bgG, bgB)

        val fgCount = (sortedByLum.size * 0.16f).toInt().coerceAtLeast(1)
        val fgSlice = if (isLightBubble) {
            sortedByLum.subList(0, fgCount)
        } else {
            sortedByLum.subList((sortedByLum.size - fgCount).coerceAtLeast(0), sortedByLum.size)
        }

        val fgR = fgSlice.map { it.r }.average().roundToInt().coerceIn(0, 255)
        val fgG = fgSlice.map { it.g }.average().roundToInt().coerceIn(0, 255)
        val fgB = fgSlice.map { it.b }.average().roundToInt().coerceIn(0, 255)
        val fgLum = 0.299f * fgR + 0.587f * fgG + 0.114f * fgB

        val textColor = if (abs(bgLum - fgLum) >= 85f) {
            Color.argb(255, fgR, fgG, fgB)
        } else if (isLightBubble) {
            Color.argb(255, 13, 15, 20)
        } else {
            Color.argb(255, 248, 246, 240)
        }

        return bgColor to textColor
    }

    /**
     * Detects Japanese, Korean, or Chinese speech bubbles on the screen bitmap,
     * automatically ignoring the top status bar and bottom navigation bar,
     * merges lines inside the same speech bubble, samples the bubble's background
     * and text colors, and translates each bubble into English.
     */
    suspend fun detectAndTranslate(
        bitmap: Bitmap,
        language: SourceLanguage,
        glossary: List<GlossaryEntry> = emptyList(),
        fallbackRegions: List<PresetBubbleRegion> = emptyList(),
        topIgnoreRatio: Float = 0.08f,
        bottomIgnoreRatio: Float = 0.94f
    ): List<DetectedBubble> = withContext(Dispatchers.Default) {
        val width = bitmap.width.coerceAtLeast(1).toFloat()
        val height = bitmap.height.coerceAtLeast(1).toFloat()

        val minTopPx = (height * topIgnoreRatio).toInt()
        val maxBottomPx = (height * bottomIgnoreRatio).toInt()

        // Step 1: Run Google ML Kit TextRecognizer for the chosen CJK script
        val mlKitBlocks: List<Text.TextBlock> = try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText = withTimeoutOrNull(8000L) {
                recognizerFor(language).process(inputImage).await()
            }
            visionText?.textBlocks?.filter { block ->
                val box = block.boundingBox
                val text = block.text.trim()
                box != null &&
                    text.isNotBlank() &&
                    containsCjkCharacters(text) &&
                    box.top >= minTopPx &&
                    box.centerY() >= minTopPx &&
                    box.bottom <= maxBottomPx &&
                    box.width() >= 14 &&
                    box.height() >= 14
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        // Step 2: Merge nearby OCR text blocks that belong to the same speech bubble
        val mergedRegions: List<RawDetectedRegion> = if (mlKitBlocks.isNotEmpty()) {
            val merged = mergeNearbyBlocksIntoBubbles(mlKitBlocks, language, width, height)
            sortRegionsForComicOrder(merged, language)
        } else if (fallbackRegions.isNotEmpty()) {
            fallbackRegions.map { preset ->
                RawDetectedRegion(
                    rawText = preset.rawText,
                    normLeft = preset.normLeft,
                    normTop = preset.normTop,
                    normRight = preset.normRight,
                    normBottom = preset.normBottom,
                    isVertical = preset.isVertical
                )
            }
        } else {
            emptyList()
        }

        if (mergedRegions.isEmpty()) return@withContext emptyList()

        // Deduplicate: if the exact same text regions at the same approximate positions were just translated,
        // update their coordinates smoothly without making redundant network API calls!
        val currentBatchKey = buildString {
            append(language.code)
            append('|')
            mergedRegions.forEach { r ->
                append(r.rawText)
                append('@')
                append((r.normTop * 20).roundToInt())
                append(';')
            }
        }
        val cachedBatch = lastBatchResult
        if (currentBatchKey == lastBatchKey && cachedBatch.size == mergedRegions.size) {
            return@withContext mergedRegions.mapIndexed { idx, r ->
                val prev = cachedBatch[idx]
                val (bgColor, txtColor) = sampleBubbleColors(bitmap, r.normLeft, r.normTop, r.normRight, r.normBottom)
                prev.copy(
                    normLeft = r.normLeft,
                    normTop = r.normTop,
                    normRight = r.normRight,
                    normBottom = r.normBottom,
                    backgroundColorArgb = bgColor,
                    textColorArgb = txtColor
                )
            }
        }

        // Step 3: Sample bubble colors and translate every bubble into English in parallel
        val translatedBubbles = coroutineScope {
            mergedRegions.mapIndexed { idx, region ->
                async(Dispatchers.IO) {
                    val cleanRaw = region.rawText.trim()
                    val (bgColor, txtColor) = sampleBubbleColors(
                        bitmap = bitmap,
                        normLeft = region.normLeft,
                        normTop = region.normTop,
                        normRight = region.normRight,
                        normBottom = region.normBottom
                    )
                    val lexiconInfo = ComicLinguisticLexicon.lookupOrAnalyze(cleanRaw, language, glossary)
                    val englishText = translateCjkToEnglish(cleanRaw, language, glossary)

                    DetectedBubble(
                        id = UUID.randomUUID().toString(),
                        index = idx + 1,
                        rawText = cleanRaw,
                        sourceLanguage = language.code,
                        normLeft = region.normLeft,
                        normTop = region.normTop,
                        normRight = region.normRight,
                        normBottom = region.normBottom,
                        mlKitTranslation = englishText,
                        geminiLocalizedText = englishText,
                        romanization = lexiconInfo.romanization,
                        literalBreakdown = lexiconInfo.literalBreakdown,
                        culturalNote = lexiconInfo.culturalNote,
                        isVertical = region.isVertical,
                        confidence = 0.98f,
                        engineUsed = "Google Translate",
                        backgroundColorArgb = bgColor,
                        textColorArgb = txtColor
                    )
                }
            }.awaitAll()
        }

        val finalBubbles = if (GeminiTranslationEngine.isApiKeyConfigured()) {
            GeminiTranslationEngine.enrichDetectedBubbles(
                bubbles = translatedBubbles,
                language = language,
                glossary = glossary,
                pageBitmap = null
            )
        } else {
            translatedBubbles
        }

        lastBatchKey = currentBatchKey
        lastBatchResult = finalBubbles
        finalBubbles
    }

    private fun mergeNearbyBlocksIntoBubbles(
        blocks: List<Text.TextBlock>,
        language: SourceLanguage,
        screenWidth: Float,
        screenHeight: Float
    ): List<RawDetectedRegion> {
        data class MutableBubble(
            var rect: Rect,
            val texts: MutableList<Pair<Rect, String>>
        )

        val clusters = mutableListOf<MutableBubble>()
        val maxHorizGap = screenWidth * 0.055f
        val maxVertGap = screenHeight * 0.032f

        for (block in blocks) {
            val box = Rect(block.boundingBox ?: continue)
            val text = block.text.replace("\n", " ").trim()
            if (text.isEmpty()) continue

            val existing = clusters.firstOrNull { cluster ->
                val cRect = cluster.rect
                val horizDist = max(0, max(cRect.left - box.right, box.left - cRect.right))
                val vertDist = max(0, max(cRect.top - box.bottom, box.top - cRect.bottom))
                horizDist <= maxHorizGap && vertDist <= maxVertGap
            }

            if (existing != null) {
                existing.rect.union(box)
                existing.texts.add(box to text)
            } else {
                clusters.add(MutableBubble(Rect(box), mutableListOf(box to text)))
            }
        }

        return clusters.map { cluster ->
            val sortedParts = if (language == SourceLanguage.JA && cluster.rect.height() > cluster.rect.width()) {
                cluster.texts.sortedWith { a, b ->
                    val xDiff = b.first.centerX() - a.first.centerX()
                    if (abs(xDiff) > 25) xDiff else a.first.top - b.first.top
                }
            } else {
                cluster.texts.sortedBy { it.first.top }
            }

            val combinedText = sortedParts.joinToString(" ") { it.second }.trim()
            val r = cluster.rect

            val padX = (screenWidth * 0.024f).toInt()
            val padY = (screenHeight * 0.014f).toInt()

            RawDetectedRegion(
                rawText = combinedText,
                normLeft = ((r.left - padX) / screenWidth).coerceIn(0.01f, 0.94f),
                normTop = ((r.top - padY) / screenHeight).coerceIn(0.06f, 0.92f),
                normRight = ((r.right + padX) / screenWidth).coerceIn(0.06f, 0.99f),
                normBottom = ((r.bottom + padY) / screenHeight).coerceIn(0.09f, 0.95f),
                isVertical = r.height() > r.width() * 1.3f
            )
        }
    }

    private fun sortRegionsForComicOrder(
        regions: List<RawDetectedRegion>,
        language: SourceLanguage
    ): List<RawDetectedRegion> {
        return regions.sortedWith { a, b ->
            val bandA = (a.normTop / 0.18f).toInt()
            val bandB = (b.normTop / 0.18f).toInt()
            if (bandA != bandB) {
                bandA.compareTo(bandB)
            } else if (language == SourceLanguage.JA) {
                b.normLeft.compareTo(a.normLeft)
            } else {
                a.normLeft.compareTo(b.normLeft)
            }
        }
    }

    private suspend fun translateCjkToEnglish(
        rawText: String,
        language: SourceLanguage,
        glossary: List<GlossaryEntry>
    ): String {
        val exactSampleMatch = ComicLinguisticLexicon.findExactEnglish(rawText)
        if (exactSampleMatch != null) {
            return applyGlossaryReplacements(rawText, exactSampleMatch, glossary)
        }

        val cacheKey = "${language.code}:$rawText"
        translationCache[cacheKey]?.let { cached ->
            return applyGlossaryReplacements(rawText, cached, glossary)
        }

        val googleTranslated = translateViaGoogleGtx(rawText, googleLangCode(language))
        if (!googleTranslated.isNullOrBlank() && !googleTranslated.equals(rawText, ignoreCase = true)) {
            translationCache[cacheKey] = googleTranslated
            return applyGlossaryReplacements(rawText, googleTranslated, glossary)
        }

        val autoTranslated = translateViaGoogleGtx(rawText, "auto")
        if (!autoTranslated.isNullOrBlank() && !autoTranslated.equals(rawText, ignoreCase = true)) {
            translationCache[cacheKey] = autoTranslated
            return applyGlossaryReplacements(rawText, autoTranslated, glossary)
        }

        val mlKitResult = translateViaMlKitOnDevice(rawText, language)
        if (!mlKitResult.isNullOrBlank()) {
            translationCache[cacheKey] = mlKitResult
            return applyGlossaryReplacements(rawText, mlKitResult, glossary)
        }

        return applyGlossaryReplacements(rawText, "[Translating...]", glossary)
    }

    private fun translateViaGoogleGtx(rawText: String, sourceLangCode: String): String? {
        return try {
            val encoded = URLEncoder.encode(rawText, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sourceLangCode&tl=en&dt=t&q=$encoded"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android)")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyStr = response.body?.string() ?: return null
                val rootArray = JSONArray(bodyStr)
                val sentences = rootArray.optJSONArray(0) ?: return null
                val sb = StringBuilder()
                for (i in 0 until sentences.length()) {
                    val seg = sentences.optJSONArray(i)
                    val piece = seg?.optString(0)
                    if (!piece.isNullOrEmpty()) {
                        sb.append(piece)
                    }
                }
                sb.toString().trim().takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun translateViaMlKitOnDevice(
        rawText: String,
        language: SourceLanguage
    ): String? {
        val translator = translatorFor(language)
        return try {
            val conditions = DownloadConditions.Builder().build()
            withTimeoutOrNull(12000L) {
                translator.downloadModelIfNeeded(conditions).await()
                translator.translate(rawText).await()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun applyGlossaryReplacements(
        rawText: String,
        translatedText: String,
        glossary: List<GlossaryEntry>
    ): String {
        var output = translatedText
        glossary.forEach { entry ->
            if (entry.sourceTerm.isNotBlank() && rawText.contains(entry.sourceTerm)) {
                if (!output.contains(entry.targetTerm, ignoreCase = true)) {
                    output = "$output (${entry.targetTerm})"
                }
            }
        }
        return output
    }
}

data class RawDetectedRegion(
    val rawText: String,
    val normLeft: Float,
    val normTop: Float,
    val normRight: Float,
    val normBottom: Float,
    val isVertical: Boolean
)

data class PresetBubbleRegion(
    val rawText: String,
    val normLeft: Float,
    val normTop: Float,
    val normRight: Float,
    val normBottom: Float,
    val isVertical: Boolean = false,
    val customBubbleBgColor: Int? = null,
    val customBubbleTextColor: Int? = null
)
