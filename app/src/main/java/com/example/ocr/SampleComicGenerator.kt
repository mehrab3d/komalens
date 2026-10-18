package com.example.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.example.data.model.GlossaryEntry
import com.example.data.model.SourceLanguage

data class SampleComicPage(
    val id: String,
    val title: String,
    val seriesName: String,
    val chapterBadge: String,
    val language: SourceLanguage,
    val description: String,
    val presetRegions: List<PresetBubbleRegion>
)

data class LexiconAnalysis(
    val englishTranslation: String,
    val localizedComicText: String,
    val romanization: String,
    val literalBreakdown: String,
    val culturalNote: String
)

object ComicLinguisticLexicon {

    private val entries: Map<String, LexiconAnalysis> = mapOf(
        // Japanese Manga (Blade of the Eclipse)
        "領域展開！無量空処！" to LexiconAnalysis(
            englishTranslation = "Domain Expansion! Unlimited Void!",
            localizedComicText = "DOMAIN EXPANSION: UNLIMITED VOID!",
            romanization = "Ryōiki Tenkai! Muryōkūsho!",
            literalBreakdown = "領域 (Domain/Territory) + 展開 (Expansion/Deployment) + 無量空処 (Immeasurable Void)",
            culturalNote = "Signature supreme jujutsu/sorcery activation phrase; rendered in bold all-caps for dramatic impact."
        ),
        "まだ終わっていない…立つんだ、黒崎先輩！" to LexiconAnalysis(
            englishTranslation = "It's not over yet... Stand up, Kurosaki-senpai!",
            localizedComicText = "It's not over yet... Get up, Kurosaki-senpai!",
            romanization = "Mada owatte inai... Tatsun da, Kurosaki-senpai!",
            literalBreakdown = "まだ (Still/Not yet) + 終わっていない (has not ended) + 立つんだ (stand up!) + 先輩 (senior/mentor)",
            culturalNote = "Uses '-senpai' honorific showing respect to an upperclassman/senior warrior; 'んだ' (nda) adds urgent emotional emphasis."
        ),
        "この一撃で闇を斬り裂く！" to LexiconAnalysis(
            englishTranslation = "With this single strike, I will slash through the darkness!",
            localizedComicText = "This one strike will cleave right through the dark!",
            romanization = "Kono ichigeki de yami o kirisaku!",
            literalBreakdown = "この (This) + 一撃 (one blow/strike) + で (with) + 闇 (darkness) + 斬り裂く (slash/cleave apart)",
            culturalNote = "Classic shonen climax declaration before a finishing sword technique."
        ),
        "ゴゴゴゴ" to LexiconAnalysis(
            englishTranslation = "Rumble... Menacing aura...",
            localizedComicText = "[SFX: MENACING RUMBLE...]",
            romanization = "Gogogogo",
            literalBreakdown = "ゴゴゴ (Onomatopoeia for heavy atmospheric pressure or menacing presence)",
            culturalNote = "Iconic manga sound effect indicating overwhelming spiritual pressure or tension."
        ),

        // Korean Manhwa (Shadow Gate Hunter)
        "일어나라… 그림자 군단이여!" to LexiconAnalysis(
            englishTranslation = "Arise... O Shadow Legion!",
            localizedComicText = "ARISE... MY SHADOW LEGION!",
            romanization = "Il-eo-na-ra... Geu-rim-ja Gun-dan-i-yeo!",
            literalBreakdown = "일어나라 (Rise/Awaken [imperative]) + 그림자 (Shadow) + 군단 (Legion/Army) + 이여 (vocative particle 'O')",
            culturalNote = "High-authority monarch command in Korean dungeon/hunter webtoons using the formal imperative '-라'."
        ),
        "성진우 헌터님, S급 게이트가 열렸습니다!" to LexiconAnalysis(
            englishTranslation = "Hunter Sung Jin-Woo, an S-Rank Gate has opened!",
            localizedComicText = "Hunter Sung Jin-Woo! An S-Rank Gate just breached!",
            romanization = "Seong Jin-u Heon-teo-nim, S-geup Ge-i-teu-ga yeol-lyeot-seum-ni-da!",
            literalBreakdown = "헌터님 (Hunter + honorific -nim) + S급 (S-Rank) + 게이트 (Gate) + 열렸습니다 (has opened [formal polite])",
            culturalNote = "The suffix '-님' (-nim) elevates professional status, while '-습니다' marks urgent formal reporting."
        ),
        "보스 몬스터의 마력이 심상치 않아…" to LexiconAnalysis(
            englishTranslation = "The boss monster's magic power is unusual...",
            localizedComicText = "This Boss Monster's mana pressure is off the charts...",
            romanization = "Bo-seu Mon-seu-teo-ui ma-ryeog-i sim-sang-chi an-a...",
            literalBreakdown = "보스 몬스터 (Boss Monster) + 마력 (mana/magical power) + 심상치 않아 (is not ordinary / ominous)",
            culturalNote = "'마력' (Maryeok) is standard Korean fantasy terminology for mana or arcane energy."
        ),
        "쿠구구궁!" to LexiconAnalysis(
            englishTranslation = "Kugugugung! (Heavy dungeon tremor)",
            localizedComicText = "[SFX: TREMOR / EARTH-SHAKING ROAR!]",
            romanization = "Ku-gu-gu-gung!",
            literalBreakdown = "쿠구구궁 (Korean phonetic SFX for a cavern or dimensional gate violently shaking)",
            culturalNote = "Frequently used in vertical action webtoons when a high-rank boss awakens."
        ),

        // Chinese Manhua (Immortal Sword Sect)
        "万剑归宗！破！" to LexiconAnalysis(
            englishTranslation = "Ten Thousand Swords Return to the Sect! Break!",
            localizedComicText = "TEN THOUSAND SWORDS RETURN TO ORIGIN — BREAK!",
            romanization = "Wàn jiàn guī zōng! Pò!",
            literalBreakdown = "万剑 (Ten thousand swords) + 归宗 (return to ancestor/origin) + 破 (shatter/break!)",
            culturalNote = "Classic wuxia/xianxia four-character idiom (chengyu-style technique name) followed by a single-syllable activation shout."
        ),
        "林师兄小心，此人已入元婴期！" to LexiconAnalysis(
            englishTranslation = "Senior Brother Lin be careful, this person has already entered the Nascent Soul stage!",
            localizedComicText = "Careful, Senior Brother Lin! He's already reached the Nascent Soul Realm!",
            romanization = "Lín shīxiōng xiǎoxīn, cǐ rén yǐ rù Yuányīng qī!",
            literalBreakdown = "师兄 (Senior martial brother) + 小心 (be careful) + 此人 (this person) + 元婴期 (Nascent Soul cultivation stage)",
            culturalNote = "'师兄' (Shixiong) denotes a senior male disciple of the same sect; '元婴期' (Yuanying) is a major xianxia cultivation realm."
        ),
        "区区凡人，也敢挡本座去路？" to LexiconAnalysis(
            englishTranslation = "A mere mortal dares to block this sovereign's path?",
            localizedComicText = "A mere mortal dares stand in This Sovereign's way?!",
            romanization = "Qūqū fánrén, yě gǎn dǎng běnzuò qùlù?",
            literalBreakdown = "区区 (Mere/trivial) + 凡人 (mortal) + 敢 (dare) + 挡 (block) + 本座 (This Seat / Sovereign self-pronoun)",
            culturalNote = "'本座' (Běnzuò, literally 'This Seat') is an arrogant, high-status pronoun used by sect elders and supreme immortals."
        )
    )

    fun findExactEnglish(rawText: String): String? {
        val clean = rawText.trim()
        return entries[clean]?.localizedComicText
    }

    fun lookupOrAnalyze(
        rawText: String,
        language: SourceLanguage,
        glossary: List<GlossaryEntry>
    ): LexiconAnalysis {
        val clean = rawText.trim()
        val matched = entries[clean]
        if (matched != null) {
            return matched
        }

        val glossaryHits = glossary.filter { it.sourceTerm.isNotBlank() && clean.contains(it.sourceTerm) }
        val glossaryNote = if (glossaryHits.isNotEmpty()) {
            "Applied custom glossary: " + glossaryHits.joinToString(", ") { "${it.sourceTerm} → ${it.targetTerm}" }
        } else {
            "Detected ${language.displayName} (${language.comicType}) speech bubble."
        }

        return LexiconAnalysis(
            englishTranslation = "",
            localizedComicText = "",
            romanization = "",
            literalBreakdown = "Script: ${language.displayName} (${language.code})",
            culturalNote = glossaryNote
        )
    }
}

object SampleComicGenerator {

    val samplePages: List<SampleComicPage> = listOf(
        SampleComicPage(
            id = "sample_jp_manga",
            title = "Blade of the Eclipse",
            seriesName = "Blade of the Eclipse (黒蝕の刃)",
            chapterBadge = "RAW CH. 142 • SHONEN MANGA",
            language = SourceLanguage.JA,
            description = "Right-to-left monochrome Japanese manga page with vertical & horizontal dialogue bubbles.",
            presetRegions = listOf(
                PresetBubbleRegion(
                    rawText = "領域展開！無量空処！",
                    normLeft = 0.54f,
                    normTop = 0.06f,
                    normRight = 0.94f,
                    normBottom = 0.24f,
                    isVertical = false
                ),
                PresetBubbleRegion(
                    rawText = "まだ終わっていない…立つんだ、黒崎先輩！",
                    normLeft = 0.06f,
                    normTop = 0.34f,
                    normRight = 0.56f,
                    normBottom = 0.53f,
                    isVertical = false
                ),
                PresetBubbleRegion(
                    rawText = "この一撃で闇を斬り裂く！",
                    normLeft = 0.36f,
                    normTop = 0.68f,
                    normRight = 0.93f,
                    normBottom = 0.87f,
                    isVertical = false,
                    customBubbleBgColor = Color.parseColor("#141824"),
                    customBubbleTextColor = Color.parseColor("#FFF8E7")
                ),
                PresetBubbleRegion(
                    rawText = "ゴゴゴゴ",
                    normLeft = 0.07f,
                    normTop = 0.72f,
                    normRight = 0.31f,
                    normBottom = 0.85f,
                    isVertical = false
                )
            )
        ),
        SampleComicPage(
            id = "sample_kr_manhwa",
            title = "Shadow Gate Hunter",
            seriesName = "Shadow Gate Hunter (그림자 헌터)",
            chapterBadge = "RAW EP. 88 • ACTION MANHWA",
            language = SourceLanguage.KO,
            description = "Full-color vertical Korean dungeon webtoon panel with both light and dark monarch speech bubbles.",
            presetRegions = listOf(
                PresetBubbleRegion(
                    rawText = "성진우 헌터님, S급 게이트가 열렸습니다!",
                    normLeft = 0.06f,
                    normTop = 0.06f,
                    normRight = 0.74f,
                    normBottom = 0.23f
                ),
                PresetBubbleRegion(
                    rawText = "보스 몬스터의 마력이 심상치 않아…",
                    normLeft = 0.24f,
                    normTop = 0.36f,
                    normRight = 0.93f,
                    normBottom = 0.53f
                ),
                PresetBubbleRegion(
                    rawText = "일어나라… 그림자 군단이여!",
                    normLeft = 0.08f,
                    normTop = 0.67f,
                    normRight = 0.88f,
                    normBottom = 0.86f,
                    customBubbleBgColor = Color.parseColor("#0E1528"),
                    customBubbleTextColor = Color.parseColor("#7CEEFF")
                )
            )
        ),
        SampleComicPage(
            id = "sample_zh_manhua",
            title = "Immortal Sword Sect",
            seriesName = "Immortal Sword Sect (万剑仙宗)",
            chapterBadge = "RAW CH. 215 • WUXIA MANHUA",
            language = SourceLanguage.ZH,
            description = "Painterly Chinese cultivation manhua scene with sect terminology and martial arts techniques.",
            presetRegions = listOf(
                PresetBubbleRegion(
                    rawText = "林师兄小心，此人已入元婴期！",
                    normLeft = 0.06f,
                    normTop = 0.07f,
                    normRight = 0.76f,
                    normBottom = 0.24f
                ),
                PresetBubbleRegion(
                    rawText = "区区凡人，也敢挡本座去路？",
                    normLeft = 0.22f,
                    normTop = 0.37f,
                    normRight = 0.93f,
                    normBottom = 0.54f
                ),
                PresetBubbleRegion(
                    rawText = "万剑归宗！破！",
                    normLeft = 0.12f,
                    normTop = 0.69f,
                    normRight = 0.88f,
                    normBottom = 0.87f,
                    customBubbleBgColor = Color.parseColor("#2A1B12"),
                    customBubbleTextColor = Color.parseColor("#FFE082")
                )
            )
        )
    )

    fun renderSamplePageBitmap(page: SampleComicPage): Bitmap {
        val width = 900
        val height = 1200
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        when (page.language) {
            SourceLanguage.JA -> drawMangaArtwork(canvas, width.toFloat(), height.toFloat(), paint)
            SourceLanguage.KO -> drawManhwaArtwork(canvas, width.toFloat(), height.toFloat(), paint)
            SourceLanguage.ZH -> drawManhuaArtwork(canvas, width.toFloat(), height.toFloat(), paint)
        }

        val bubbleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val bubbleBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 34f
        }

        page.presetRegions.forEach { region ->
            val rect = RectF(
                region.normLeft * width,
                region.normTop * height,
                region.normRight * width,
                region.normBottom * height
            )
            val shadowRect = RectF(rect.left + 6f, rect.top + 6f, rect.right + 6f, rect.bottom + 6f)
            paint.color = Color.argb(110, 0, 0, 0)
            canvas.drawRoundRect(shadowRect, 28f, 28f, paint)

            val bgCol = region.customBubbleBgColor ?: Color.parseColor("#FAF8F2")
            val txtCol = region.customBubbleTextColor ?: Color.parseColor("#0D0F14")

            bubbleFill.color = bgCol
            bubbleBorder.color = if (region.customBubbleBgColor != null) txtCol else Color.parseColor("#111318")
            textPaint.color = txtCol

            canvas.drawRoundRect(rect, 28f, 28f, bubbleFill)
            canvas.drawRoundRect(rect, 28f, 28f, bubbleBorder)

            drawWrappedCjkText(canvas, region.rawText, rect, textPaint)
        }

        return bitmap
    }

    private fun drawWrappedCjkText(canvas: Canvas, text: String, rect: RectF, paint: Paint) {
        val padding = 24f
        val availWidth = (rect.width() - padding * 2).coerceAtLeast(60f)
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (ch in text) {
            val candidate = currentLine.toString() + ch
            if (paint.measureText(candidate) > availWidth && currentLine.isNotEmpty()) {
                lines.add(currentLine.toString())
                currentLine = StringBuilder().append(ch)
            } else {
                currentLine.append(ch)
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }

        val lineHeight = paint.textSize * 1.32f
        val totalHeight = lines.size * lineHeight
        var y = rect.centerY() - (totalHeight / 2f) - paint.ascent() * 0.85f

        for (line in lines) {
            val lineWidth = paint.measureText(line)
            val x = rect.centerX() - lineWidth / 2f
            canvas.drawText(line, x, y, paint)
            y += lineHeight
        }
    }

    private fun drawMangaArtwork(canvas: Canvas, w: Float, h: Float, paint: Paint) {
        canvas.drawColor(Color.parseColor("#E8E5DC"))

        val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#181A20")
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 9f
        }

        val p1 = RectF(28f, 28f, w - 28f, h * 0.31f)
        canvas.drawRect(p1, panelPaint)
        drawSpeedLines(canvas, p1, Color.parseColor("#383D4C"))
        paint.color = Color.parseColor("#00E5FF")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        canvas.drawCircle(w * 0.28f, h * 0.17f, 95f, paint)
        canvas.drawCircle(w * 0.28f, h * 0.17f, 60f, paint)
        canvas.drawRect(p1, borderPaint)

        val p2 = RectF(28f, h * 0.33f, w - 28f, h * 0.63f)
        panelPaint.color = Color.parseColor("#232733")
        canvas.drawRect(p2, panelPaint)
        drawSpeedLines(canvas, p2, Color.parseColor("#454D66"))
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#0B0D12")
        canvas.drawCircle(w * 0.75f, h * 0.46f, 64f, paint)
        val slashPath = Path().apply {
            moveTo(w * 0.12f, h * 0.60f)
            lineTo(w * 0.88f, h * 0.36f)
            lineTo(w * 0.92f, h * 0.39f)
            close()
        }
        paint.color = Color.parseColor("#FF3366")
        canvas.drawPath(slashPath, paint)
        canvas.drawRect(p2, borderPaint)

        val p3 = RectF(28f, h * 0.65f, w - 28f, h - 28f)
        panelPaint.color = Color.parseColor("#101218")
        canvas.drawRect(p3, panelPaint)
        drawSpeedLines(canvas, p3, Color.parseColor("#00E5FF"))
        canvas.drawRect(p3, borderPaint)
    }

    private fun drawManhwaArtwork(canvas: Canvas, w: Float, h: Float, paint: Paint) {
        val bgGradient = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.parseColor("#070B19"),
                Color.parseColor("#111B3A"),
                Color.parseColor("#090D1C")
            ),
            null,
            Shader.TileMode.CLAMP
        )
        paint.shader = bgGradient
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        for (i in 1..6) {
            paint.color = Color.argb(220 - i * 30, 0, 229, 255)
            paint.strokeWidth = (18 - i * 2).toFloat()
            canvas.drawOval(
                RectF(w * 0.15f + i * 14, h * 0.18f + i * 14, w * 0.85f - i * 14, h * 0.82f - i * 14),
                paint
            )
        }

        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#05070E")
        val cloakPath = Path().apply {
            moveTo(w * 0.5f, h * 0.32f)
            lineTo(w * 0.72f, h * 0.92f)
            lineTo(w * 0.28f, h * 0.92f)
            close()
        }
        canvas.drawPath(cloakPath, paint)
        paint.color = Color.parseColor("#00E5FF")
        canvas.drawCircle(w * 0.46f, h * 0.42f, 8f, paint)
        canvas.drawCircle(w * 0.54f, h * 0.42f, 8f, paint)
    }

    private fun drawManhuaArtwork(canvas: Canvas, w: Float, h: Float, paint: Paint) {
        val skyGradient = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(
                Color.parseColor("#132226"),
                Color.parseColor("#1F3A3D"),
                Color.parseColor("#2E2338")
            ),
            null,
            Shader.TileMode.CLAMP
        )
        paint.shader = skyGradient
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        paint.color = Color.parseColor("#FFD54F")
        paint.strokeWidth = 6f
        for (i in 0..7) {
            val sx = w * (0.12f + i * 0.11f)
            val sy = h * (0.22f + (i % 3) * 0.14f)
            canvas.drawLine(sx, sy, sx + 35f, sy + 110f, paint)
        }

        val mountain = Path().apply {
            moveTo(0f, h)
            lineTo(0f, h * 0.65f)
            lineTo(w * 0.32f, h * 0.48f)
            lineTo(w * 0.62f, h * 0.68f)
            lineTo(w * 0.85f, h * 0.44f)
            lineTo(w, h * 0.60f)
            lineTo(w, h)
            close()
        }
        paint.color = Color.parseColor("#0E171B")
        paint.style = Paint.Style.FILL
        canvas.drawPath(mountain, paint)
    }

    private fun drawSpeedLines(canvas: Canvas, rect: RectF, color: Int) {
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = 2.5f
        }
        val step = 28f
        var x = rect.left
        while (x < rect.right) {
            canvas.drawLine(x, rect.top, rect.centerX(), rect.centerY(), linePaint)
            canvas.drawLine(x, rect.bottom, rect.centerX(), rect.centerY(), linePaint)
            x += step
        }
    }
}
