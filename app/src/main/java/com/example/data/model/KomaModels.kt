package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import kotlinx.serialization.Serializable

enum class SourceLanguage(
    val code: String,
    val displayName: String,
    val comicType: String,
    val romanizationName: String
) {
    JA("JA", "Japanese", "Manga (RTL)", "Romaji"),
    KO("KO", "Korean", "Manhwa (Webtoon)", "Revised Romanization"),
    ZH("ZH", "Chinese", "Manhua (Wuxia)", "Pinyin");

    companion object {
        fun fromCode(code: String): SourceLanguage =
            entries.find { it.code.equals(code, ignoreCase = true) } ?: JA
    }
}

enum class OverlayDisplayMode(val label: String) {
    TRANSLATED("Translated Bubbles"),
    RAW_PEEK("Raw CJK + Boxes"),
    SPLIT_LIST("Side-by-Side List")
}

@Serializable
data class DetectedBubble(
    val id: String = "",
    val index: Int = 1,
    val rawText: String = "",
    val sourceLanguage: String = "JA",
    // Normalized coordinates (0f..1f) relative to bitmap width & height
    val normLeft: Float = 0.1f,
    val normTop: Float = 0.1f,
    val normRight: Float = 0.4f,
    val normBottom: Float = 0.25f,
    val mlKitTranslation: String = "",
    val geminiLocalizedText: String = "",
    val romanization: String = "",
    val literalBreakdown: String = "",
    val culturalNote: String = "",
    val isVertical: Boolean = false,
    val confidence: Float = 0.96f,
    val engineUsed: String = "Google Translate",
    val backgroundColorArgb: Int = 0xFFFAF8F2.toInt(),
    val textColorArgb: Int = 0xFF0D0F14.toInt()
) {
    val activeTranslation: String
        get() = geminiLocalizedText.ifBlank { mlKitTranslation.ifBlank { rawText } }
}

@IgnoreExtraProperties
data class GlossaryEntry(
    @DocumentId val id: String = "",
    val userId: String = "",
    val sourceTerm: String = "",
    val targetTerm: String = "",
    val sourceLanguage: String = "JA",
    val seriesTitle: String = "General",
    val category: String = "Character",
    val notes: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(authenticatedUid: String): Map<String, Any> {
        return mapOf(
            "userId" to authenticatedUid,
            "sourceTerm" to sourceTerm.trim(),
            "targetTerm" to targetTerm.trim(),
            "sourceLanguage" to sourceLanguage,
            "seriesTitle" to seriesTitle.trim().ifBlank { "General" },
            "category" to category,
            "notes" to notes?.trim()?.takeIf { it.isNotEmpty() },
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null }.mapValues { it.value!! }
    }
}

@IgnoreExtraProperties
data class TranslationHistoryItem(
    @DocumentId val id: String = "",
    val userId: String = "",
    val pageTitle: String = "",
    val sourceLanguage: String = "JA",
    val sourceType: String = "Reader",
    val bubbleCount: Long = 0L,
    val rawPreview: String = "",
    val translatedPreview: String = "",
    val bubblesPayload: String = "[]",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(authenticatedUid: String): Map<String, Any> {
        return mapOf(
            "userId" to authenticatedUid,
            "sourceTitle" to pageTitle.trim().ifBlank { "Untitled Scan" },
            "pageTitle" to pageTitle.trim().ifBlank { "Untitled Scan" },
            "sourceLanguage" to sourceLanguage,
            "sourceType" to sourceType,
            "bubbleCount" to bubbleCount,
            "rawPreview" to rawPreview.take(4500),
            "translatedPreview" to translatedPreview.take(4500),
            "bubblesPayload" to bubblesPayload.take(95000),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterKeys { it != "sourceTitle" }
    }
}

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
