package com.example.network

import android.graphics.Bitmap
import android.util.Base64
import com.example.BuildConfig
import com.example.data.model.DetectedBubble
import com.example.data.model.GlossaryEntry
import com.example.data.model.SourceLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

@Serializable
data class GenerateContentRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig? = null,
    val systemInstruction: Content? = null
)

@Serializable
data class Content(
    val parts: List<Part>
)

@Serializable
data class Part(
    val text: String? = null,
    val inlineData: InlineData? = null
)

@Serializable
data class InlineData(
    val mimeType: String,
    val data: String
)

@Serializable
data class GenerationConfig(
    val responseMimeType: String? = null,
    val responseSchema: JsonObject? = null,
    val temperature: Float? = null
)

@Serializable
data class GenerateContentResponse(
    val candidates: List<Candidate> = emptyList()
)

@Serializable
data class Candidate(
    val content: Content? = null
)

@Serializable
data class GeminiBubbleAnnotation(
    val index: Int = 1,
    val localizedText: String = "",
    val romanization: String = "",
    val literalBreakdown: String = "",
    val culturalNote: String = ""
)

interface GeminiApiService {
    @POST("v1beta/models/gemini-3.5-flash:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse
}

object GeminiTranslationEngine {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val service: GeminiApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GeminiApiService::class.java)
    }

    fun isApiKeyConfigured(): Boolean {
        val key = BuildConfig.GEMINI_API_KEY
        return key.isNotBlank() && key != "MY_GEMINI_API_KEY"
    }

    private fun Bitmap.toBase64Jpeg(maxDimension: Int = 1024): String {
        val ratio = minOf(
            maxDimension.toFloat() / width.coerceAtLeast(1),
            maxDimension.toFloat() / height.coerceAtLeast(1),
            1f
        )
        val scaled = if (ratio < 1f) {
            Bitmap.createScaledBitmap(
                this,
                (width * ratio).toInt().coerceAtLeast(1),
                (height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else {
            this
        }
        val outputStream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    suspend fun enrichDetectedBubbles(
        bubbles: List<DetectedBubble>,
        language: SourceLanguage,
        glossary: List<GlossaryEntry>,
        pageBitmap: Bitmap? = null
    ): List<DetectedBubble> = withContext(Dispatchers.IO) {
        if (bubbles.isEmpty()) return@withContext emptyList()
        if (!isApiKeyConfigured()) {
            return@withContext bubbles
        }

        val glossaryPrompt = if (glossary.isEmpty()) {
            "No custom glossary entries provided."
        } else {
            "Mandatory Glossary Terms (enforce these exact translations):\n" +
                glossary.joinToString("\n") {
                    "- ${it.sourceTerm} -> ${it.targetTerm} (${it.category}, Series: ${it.seriesTitle})"
                }
        }

        val bubblesInput = bubbles.joinToString("\n") { b ->
            "Bubble #${b.index}: Raw=\"${b.rawText}\" | BaselineMLKit=\"${b.mlKitTranslation}\""
        }

        val prompt = """
            You are an expert ${language.displayName} (${language.comicType}) translator and typesetter.
            Translate and analyze each detected comic speech bubble into natural, punchy English comic dialogue.
            
            $glossaryPrompt
            
            Detected Speech Bubbles:
            $bubblesInput
            
            Return a JSON array where each item corresponds to the bubble `index` and provides:
            - `index`: integer bubble index
            - `localizedText`: natural English dialogue suitable for inside a comic speech bubble
            - `romanization`: full ${language.romanizationName} reading of the raw text
            - `literalBreakdown`: word-by-word or grammar breakdown
            - `culturalNote`: brief note on honorifics, tone, martial arts/fantasy terminology, or SFX nuance
        """.trimIndent()

        val schema = buildJsonObject {
            put("type", "ARRAY")
            putJsonObject("items") {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    putJsonObject("index") { put("type", "INTEGER") }
                    putJsonObject("localizedText") { put("type", "STRING") }
                    putJsonObject("romanization") { put("type", "STRING") }
                    putJsonObject("literalBreakdown") { put("type", "STRING") }
                    putJsonObject("culturalNote") { put("type", "STRING") }
                }
            }
        }

        val parts = mutableListOf<Part>()
        parts.add(Part(text = prompt))
        if (pageBitmap != null) {
            parts.add(
                Part(
                    inlineData = InlineData(
                        mimeType = "image/jpeg",
                        data = pageBitmap.toBase64Jpeg()
                    )
                )
            )
        }

        val request = GenerateContentRequest(
            contents = listOf(Content(parts = parts)),
            generationConfig = GenerationConfig(
                responseMimeType = "application/json",
                responseSchema = schema,
                temperature = 0.3f
            ),
            systemInstruction = Content(
                parts = listOf(
                    Part(text = "You are KomaLens, a precision Manga, Manhwa, and Manhua localization engine.")
                )
            )
        )

        try {
            val response = service.generateContent(BuildConfig.GEMINI_API_KEY, request)
            val rawJson = response.candidates.firstOrNull()
                ?.content?.parts?.firstOrNull()?.text
                ?: return@withContext bubbles

            val annotations = json.decodeFromString<List<GeminiBubbleAnnotation>>(rawJson)
            val byIndex = annotations.associateBy { it.index }

            bubbles.map { bubble ->
                val match = byIndex[bubble.index]
                if (match != null) {
                    bubble.copy(
                        geminiLocalizedText = match.localizedText.ifBlank { bubble.geminiLocalizedText },
                        romanization = match.romanization.ifBlank { bubble.romanization },
                        literalBreakdown = match.literalBreakdown.ifBlank { bubble.literalBreakdown },
                        culturalNote = match.culturalNote.ifBlank { bubble.culturalNote },
                        engineUsed = "ML Kit OCR + Gemini 3.5 Flash"
                    )
                } else {
                    bubble
                }
            }
        } catch (e: Exception) {
            bubbles
        }
    }
}
