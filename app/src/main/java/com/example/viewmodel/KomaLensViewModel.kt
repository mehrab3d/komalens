package com.example.viewmodel

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.DetectedBubble
import com.example.data.model.GlossaryEntry
import com.example.data.model.OverlayDisplayMode
import com.example.data.model.SourceLanguage
import com.example.data.model.TranslationHistoryItem
import com.example.data.model.UiState
import com.example.data.repository.KomaLensRepository
import com.example.network.GeminiTranslationEngine
import com.example.ocr.MangaOcrPipeline
import com.example.ocr.SampleComicGenerator
import com.example.ocr.SampleComicPage
import com.example.overlay.OverlayStateHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ReaderUiState(
    val selectedLanguage: SourceLanguage = SourceLanguage.JA,
    val activeSamplePage: SampleComicPage = SampleComicGenerator.samplePages.first(),
    val customBitmap: Bitmap? = null,
    val customPageTitle: String? = null,
    val webtoonUrl: String = "https://m.comic.naver.com/",
    val isWebtoonMode: Boolean = false,
    val isScanning: Boolean = false,
    val isCropRegionEnabled: Boolean = false,
    // Normalized crop region (left, top, right, bottom) in 0f..1f for scanning a portion of the screen
    val cropRegion: RectF = RectF(0.04f, 0.04f, 0.96f, 0.96f),
    val detectedBubbles: List<DetectedBubble> = emptyList(),
    val selectedBubble: DetectedBubble? = null,
    val displayMode: OverlayDisplayMode = OverlayDisplayMode.TRANSLATED,
    val statusBannerMessage: String? = null,
    val isGeminiConfigured: Boolean = GeminiTranslationEngine.isApiKeyConfigured()
)

class KomaLensViewModel(
    private val repository: KomaLensRepository,
    private val currentUserId: String
) : ViewModel() {

    private val _readerState = MutableStateFlow(ReaderUiState())
    val readerState: StateFlow<ReaderUiState> = _readerState.asStateFlow()

    private val _activePageBitmap = MutableStateFlow<Bitmap>(
        SampleComicGenerator.renderSamplePageBitmap(SampleComicGenerator.samplePages.first())
    )
    val activePageBitmap: StateFlow<Bitmap> = _activePageBitmap.asStateFlow()

    val glossaryState: StateFlow<UiState<List<GlossaryEntry>>> = repository
        .observeGlossary(currentUserId)
        .onEach { list -> OverlayStateHolder.cachedGlossary = list }
        .map<List<GlossaryEntry>, UiState<List<GlossaryEntry>>> { UiState.Success(it) }
        .catch { error ->
            Log.w(TAG, "Error observing glossary", error)
            emit(UiState.Error(error.message ?: "Failed to load glossary"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    val historyState: StateFlow<UiState<List<TranslationHistoryItem>>> = repository
        .observeHistory(currentUserId)
        .map<List<TranslationHistoryItem>, UiState<List<TranslationHistoryItem>>> { UiState.Success(it) }
        .catch { error ->
            Log.w(TAG, "Error observing history", error)
            emit(UiState.Error(error.message ?: "Failed to load translation history"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    init {
        refreshOfflineModelStatus()
        // Perform initial scan on the default manga sample page so user immediately sees live OCR + translation
        scanCurrentPage(autoSaveToHistory = false)
    }

    fun refreshOfflineModelStatus() {
        viewModelScope.launch {
            val states = MangaOcrPipeline.checkAllOfflineModels()
            OverlayStateHolder.setAllOfflineModelStates(states)
        }
    }

    fun preDownloadOfflineModels(prioritizeLanguage: SourceLanguage = OverlayStateHolder.activeLanguage.value) {
        if (OverlayStateHolder.isPreDownloadingModels.value) return
        viewModelScope.launch {
            OverlayStateHolder.setPreDownloadingModels(true)
            try {
                // Download the prioritized active language first, then ensure the other CJK packs are also ready
                val orderedLanguages = listOf(prioritizeLanguage) +
                    SourceLanguage.entries.filter { it != prioritizeLanguage }

                for (lang in orderedLanguages) {
                    val current = OverlayStateHolder.offlineModelStates.value[lang]
                    if (current == com.example.ocr.OfflineModelState.READY) continue

                    OverlayStateHolder.updateOfflineModelState(lang, com.example.ocr.OfflineModelState.DOWNLOADING)
                    val ok = MangaOcrPipeline.preDownloadOfflineModel(lang)
                    OverlayStateHolder.updateOfflineModelState(
                        lang,
                        if (ok) com.example.ocr.OfflineModelState.READY else com.example.ocr.OfflineModelState.FAILED
                    )
                }
            } finally {
                OverlayStateHolder.setPreDownloadingModels(false)
            }
        }
    }

    fun selectLanguage(language: SourceLanguage) {
        OverlayStateHolder.setLanguage(language)
        val matchingSample = SampleComicGenerator.samplePages.firstOrNull { it.language == language }
            ?: _readerState.value.activeSamplePage

        if (_readerState.value.customBitmap == null) {
            _activePageBitmap.value = SampleComicGenerator.renderSamplePageBitmap(matchingSample)
            _readerState.value = _readerState.value.copy(
                selectedLanguage = language,
                activeSamplePage = matchingSample,
                selectedBubble = null
            )
        } else {
            _readerState.value = _readerState.value.copy(
                selectedLanguage = language,
                selectedBubble = null
            )
        }
        scanCurrentPage(autoSaveToHistory = false)
    }

    fun selectSamplePage(sample: SampleComicPage) {
        OverlayStateHolder.setLanguage(sample.language)
        _activePageBitmap.value = SampleComicGenerator.renderSamplePageBitmap(sample)
        _readerState.value = _readerState.value.copy(
            selectedLanguage = sample.language,
            activeSamplePage = sample,
            customBitmap = null,
            customPageTitle = null,
            isWebtoonMode = false,
            selectedBubble = null
        )
        scanCurrentPage(autoSaveToHistory = false)
    }

    fun setCustomImageBitmap(bitmap: Bitmap, title: String) {
        _activePageBitmap.value = bitmap
        _readerState.value = _readerState.value.copy(
            customBitmap = bitmap,
            customPageTitle = title,
            isWebtoonMode = false,
            selectedBubble = null,
            statusBannerMessage = "Loaded local page: $title"
        )
        scanCurrentPage(autoSaveToHistory = false)
    }

    fun setWebtoonMode(enabled: Boolean, url: String = _readerState.value.webtoonUrl) {
        _readerState.value = _readerState.value.copy(
            isWebtoonMode = enabled,
            webtoonUrl = url
        )
    }

    fun toggleCropRegionMode(enabled: Boolean) {
        val defaultCrop = if (enabled) {
            RectF(0.05f, 0.05f, 0.95f, 0.60f)
        } else {
            RectF(0.04f, 0.04f, 0.96f, 0.96f)
        }
        _readerState.value = _readerState.value.copy(
            isCropRegionEnabled = enabled,
            cropRegion = defaultCrop
        )
    }

    fun updateCropRegion(left: Float, top: Float, right: Float, bottom: Float) {
        val safeLeft = left.coerceIn(0.0f, 0.85f)
        val safeTop = top.coerceIn(0.0f, 0.85f)
        val safeRight = right.coerceIn(safeLeft + 0.12f, 1.0f)
        val safeBottom = bottom.coerceIn(safeTop + 0.12f, 1.0f)
        _readerState.value = _readerState.value.copy(
            cropRegion = RectF(safeLeft, safeTop, safeRight, safeBottom)
        )
    }

    fun setDisplayMode(mode: OverlayDisplayMode) {
        _readerState.value = _readerState.value.copy(displayMode = mode)
    }

    fun selectBubble(bubble: DetectedBubble?) {
        _readerState.value = _readerState.value.copy(selectedBubble = bubble)
    }

    fun clearStatusBanner() {
        _readerState.value = _readerState.value.copy(statusBannerMessage = null)
    }

    fun scanCurrentPage(autoSaveToHistory: Boolean = false) {
        val state = _readerState.value
        val fullBitmap = _activePageBitmap.value
        _readerState.value = state.copy(isScanning = true, statusBannerMessage = null)

        viewModelScope.launch {
            val currentGlossary = (glossaryState.value as? UiState.Success)?.data ?: emptyList()

            // If Region Crop is enabled, crop that portion of the screen bitmap first and map coordinates back!
            val crop = state.cropRegion
            val isCropped = state.isCropRegionEnabled
            val bitmapForOcr = if (isCropped) {
                val x = (crop.left * fullBitmap.width).toInt().coerceIn(0, fullBitmap.width - 10)
                val y = (crop.top * fullBitmap.height).toInt().coerceIn(0, fullBitmap.height - 10)
                val w = ((crop.right - crop.left) * fullBitmap.width).toInt().coerceIn(10, fullBitmap.width - x)
                val h = ((crop.bottom - crop.top) * fullBitmap.height).toInt().coerceIn(10, fullBitmap.height - y)
                Bitmap.createBitmap(fullBitmap, x, y, w, h)
            } else {
                fullBitmap
            }

            val fallbackPresets = if (state.customBitmap == null) {
                state.activeSamplePage.presetRegions.filter { region ->
                    if (!isCropped) true
                    else {
                        val centerY = (region.normTop + region.normBottom) / 2f
                        val centerX = (region.normLeft + region.normRight) / 2f
                        centerX in crop.left..crop.right && centerY in crop.top..crop.bottom
                    }
                }
            } else {
                emptyList()
            }

            val rawDetected = MangaOcrPipeline.detectAndTranslate(
                bitmap = bitmapForOcr,
                language = state.selectedLanguage,
                glossary = currentGlossary,
                fallbackRegions = fallbackPresets
            )

            // If we cropped a sub-region and ML Kit returned coordinates relative to the cropped bitmap,
            // remap normalized coordinates back to the full page canvas if they aren't already preset coordinates.
            val finalBubbles = if (isCropped && state.customBitmap != null) {
                val cropW = crop.right - crop.left
                val cropH = crop.bottom - crop.top
                rawDetected.map { b ->
                    b.copy(
                        normLeft = (crop.left + b.normLeft * cropW).coerceIn(0.01f, 0.95f),
                        normTop = (crop.top + b.normTop * cropH).coerceIn(0.01f, 0.95f),
                        normRight = (crop.left + b.normRight * cropW).coerceIn(0.05f, 0.99f),
                        normBottom = (crop.top + b.normBottom * cropH).coerceIn(0.05f, 0.99f)
                    )
                }
            } else {
                rawDetected
            }

            _readerState.value = _readerState.value.copy(
                isScanning = false,
                detectedBubbles = finalBubbles,
                selectedBubble = finalBubbles.firstOrNull(),
                statusBannerMessage = "Translated ${finalBubbles.size} bubbles (${state.selectedLanguage.displayName})"
            )

            if (autoSaveToHistory && finalBubbles.isNotEmpty()) {
                saveCurrentScanToHistory()
            }
        }
    }

    fun saveCurrentScanToHistory() {
        val state = _readerState.value
        val bubbles = state.detectedBubbles
        if (bubbles.isEmpty()) {
            _readerState.value = state.copy(statusBannerMessage = "No detected speech bubbles to save yet.")
            return
        }
        val title = state.customPageTitle
            ?: if (state.isWebtoonMode) "Webtoon Capture (${state.selectedLanguage.code})"
            else "${state.activeSamplePage.title} (${state.activeSamplePage.chapterBadge})"
        val sourceType = if (state.isWebtoonMode) "Webtoon" else "Reader"

        viewModelScope.launch {
            val result = repository.saveTranslationHistory(
                pageTitle = title,
                sourceLanguage = state.selectedLanguage.code,
                sourceType = sourceType,
                bubbles = bubbles
            )
            result.onSuccess {
                _readerState.value = _readerState.value.copy(
                    statusBannerMessage = "Saved \"${title}\" (${bubbles.size} bubbles) to Cloud History!"
                )
            }.onFailure { err ->
                _readerState.value = _readerState.value.copy(
                    statusBannerMessage = "Could not save history: ${err.localizedMessage}"
                )
            }
        }
    }

    fun saveOverlayCaptureToHistory(bubbles: List<DetectedBubble>, language: SourceLanguage) {
        if (bubbles.isEmpty()) return
        viewModelScope.launch {
            val result = repository.saveTranslationHistory(
                pageTitle = "Screen Overlay Scan (${language.displayName})",
                sourceLanguage = language.code,
                sourceType = "ScreenOverlay",
                bubbles = bubbles
            )
            result.onSuccess {
                _readerState.value = _readerState.value.copy(
                    statusBannerMessage = "Saved Screen Overlay capture (${bubbles.size} bubbles) to History!"
                )
            }
        }
    }

    fun loadHistorySessionIntoReader(item: TranslationHistoryItem) {
        val lang = SourceLanguage.fromCode(item.sourceLanguage)
        val decodedBubbles = repository.decodeBubbles(item.bubblesPayload)
        val matchingSample = SampleComicGenerator.samplePages.firstOrNull { it.language == lang }
            ?: SampleComicGenerator.samplePages.first()
        _activePageBitmap.value = SampleComicGenerator.renderSamplePageBitmap(matchingSample)
        _readerState.value = _readerState.value.copy(
            selectedLanguage = lang,
            activeSamplePage = matchingSample,
            customBitmap = null,
            customPageTitle = item.pageTitle,
            isWebtoonMode = false,
            detectedBubbles = decodedBubbles,
            selectedBubble = decodedBubbles.firstOrNull(),
            statusBannerMessage = "Restored session: ${item.pageTitle}"
        )
    }

    fun addGlossaryTerm(
        sourceTerm: String,
        targetTerm: String,
        sourceLanguage: String,
        seriesTitle: String,
        category: String,
        notes: String?
    ) {
        if (sourceTerm.isBlank() || targetTerm.isBlank()) return
        viewModelScope.launch {
            val entry = GlossaryEntry(
                sourceTerm = sourceTerm.trim(),
                targetTerm = targetTerm.trim(),
                sourceLanguage = sourceLanguage,
                seriesTitle = seriesTitle.trim().ifBlank { "General" },
                category = category,
                notes = notes?.trim()?.takeIf { it.isNotEmpty() }
            )
            val result = repository.addGlossaryEntry(entry)
            result.onSuccess {
                _readerState.value = _readerState.value.copy(
                    statusBannerMessage = "Added \"${sourceTerm.trim()} → ${targetTerm.trim()}\" to Glossary!"
                )
                // Re-scan active bubbles with updated glossary
                scanCurrentPage(autoSaveToHistory = false)
            }.onFailure { err ->
                _readerState.value = _readerState.value.copy(
                    statusBannerMessage = "Failed to add glossary term: ${err.localizedMessage}"
                )
            }
        }
    }

    fun deleteGlossaryTerm(entryId: String) {
        viewModelScope.launch {
            repository.deleteGlossaryEntry(entryId)
        }
    }

    fun deleteHistorySession(historyId: String) {
        viewModelScope.launch {
            repository.deleteHistoryItem(historyId)
        }
    }

    fun seedStarterGlossaryIfEmpty() {
        val current = (glossaryState.value as? UiState.Success)?.data ?: return
        if (current.isNotEmpty()) return
        viewModelScope.launch {
            val starters = listOf(
                GlossaryEntry(
                    sourceTerm = "黒崎先輩",
                    targetTerm = "Kurosaki-senpai",
                    sourceLanguage = "JA",
                    seriesTitle = "Blade of the Eclipse",
                    category = "Character",
                    notes = "Keep -senpai honorific intact"
                ),
                GlossaryEntry(
                    sourceTerm = "성진우",
                    targetTerm = "Sung Jin-Woo",
                    sourceLanguage = "KO",
                    seriesTitle = "Shadow Gate Hunter",
                    category = "Character",
                    notes = "S-Rank Shadow Monarch protagonist"
                ),
                GlossaryEntry(
                    sourceTerm = "元婴期",
                    targetTerm = "Nascent Soul Realm",
                    sourceLanguage = "ZH",
                    seriesTitle = "Immortal Sword Sect",
                    category = "Skill",
                    notes = "Major xianxia cultivation stage above Golden Core"
                )
            )
            starters.forEach { repository.addGlossaryEntry(it) }
        }
    }

    private companion object {
        const val TAG = "KomaLensVM"
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
