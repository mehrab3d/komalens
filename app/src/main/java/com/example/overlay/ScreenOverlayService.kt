package com.example.overlay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.DetectedBubble
import com.example.data.model.GlossaryEntry
import com.example.data.model.SourceLanguage
import com.example.ocr.MangaOcrPipeline
import com.example.ocr.OfflineModelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

object OverlayStateHolder {
    private val _isOverlayRunning = MutableStateFlow(false)
    val isOverlayRunning: StateFlow<Boolean> = _isOverlayRunning.asStateFlow()

    private val _isMediaProjectionActive = MutableStateFlow(false)
    val isMediaProjectionActive: StateFlow<Boolean> = _isMediaProjectionActive.asStateFlow()

    private val _isAutoScanEnabled = MutableStateFlow(true)
    val isAutoScanEnabled: StateFlow<Boolean> = _isAutoScanEnabled.asStateFlow()

    private val _activeLanguage = MutableStateFlow(SourceLanguage.KO)
    val activeLanguage: StateFlow<SourceLanguage> = _activeLanguage.asStateFlow()

    private val _offlineModelStates = MutableStateFlow(
        SourceLanguage.entries.associateWith { OfflineModelState.NOT_DOWNLOADED }
    )
    val offlineModelStates: StateFlow<Map<SourceLanguage, OfflineModelState>> = _offlineModelStates.asStateFlow()

    private val _isPreDownloadingModels = MutableStateFlow(false)
    val isPreDownloadingModels: StateFlow<Boolean> = _isPreDownloadingModels.asStateFlow()

    private val _latestOverlayBubbles = MutableStateFlow<List<DetectedBubble>>(emptyList())
    val latestOverlayBubbles: StateFlow<List<DetectedBubble>> = _latestOverlayBubbles.asStateFlow()

    private val _lastCaptureStatus = MutableStateFlow("Ready to translate screen")
    val lastCaptureStatus: StateFlow<String> = _lastCaptureStatus.asStateFlow()

    var cachedGlossary: List<GlossaryEntry> = emptyList()

    fun setOverlayRunning(running: Boolean) {
        _isOverlayRunning.value = running
    }

    fun setProjectionActive(active: Boolean) {
        _isMediaProjectionActive.value = active
    }

    fun setAutoScanEnabled(enabled: Boolean) {
        _isAutoScanEnabled.value = enabled
    }

    fun setLanguage(language: SourceLanguage) {
        _activeLanguage.value = language
    }

    fun setAllOfflineModelStates(states: Map<SourceLanguage, OfflineModelState>) {
        _offlineModelStates.value = states
    }

    fun updateOfflineModelState(language: SourceLanguage, state: OfflineModelState) {
        _offlineModelStates.value = _offlineModelStates.value.toMutableMap().apply {
            put(language, state)
        }
    }

    fun setPreDownloadingModels(downloading: Boolean) {
        _isPreDownloadingModels.value = downloading
    }

    fun updateResults(bubbles: List<DetectedBubble>, status: String) {
        _latestOverlayBubbles.value = bubbles
        _lastCaptureStatus.value = status
    }
}

/**
 * Full-screen transparent overlay canvas that draws 100% opaque translated English speech bubbles
 * directly over the foreign Korean/Japanese/Chinese text on the user's screen,
 * matching each comic bubble's sampled background and text colors.
 */
class ScreenBubbleOverlayView(context: Context) : View(context) {

    private var bubbles: List<DetectedBubble> = emptyList()
    private val drawnNormalizedRects = mutableListOf<RectF>()

    private val bubbleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FAF8F2")
        style = Paint.Style.FILL
    }

    private val bubbleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#181C26")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D0F14")
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    fun setDetectedBubbles(newBubbles: List<DetectedBubble>) {
        bubbles = newBubbles
        visibility = if (newBubbles.isEmpty()) GONE else VISIBLE
        invalidate()
    }

    fun clearBubbles() {
        bubbles = emptyList()
        drawnNormalizedRects.clear()
        visibility = GONE
        invalidate()
    }

    fun currentDrawnNormalizedRects(): List<RectF> = drawnNormalizedRects.toList()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawnNormalizedRects.clear()
        if (bubbles.isEmpty()) return

        val viewW = width.toFloat().coerceAtLeast(1f)
        val viewH = height.toFloat().coerceAtLeast(1f)
        val density = resources.displayMetrics.density

        for (bubble in bubbles) {
            val englishText = bubble.activeTranslation.trim()
            if (englishText.isEmpty()) continue

            val extraCoverX = 6f * density
            val extraCoverY = 4f * density
            val left = (bubble.normLeft * viewW - extraCoverX).coerceIn(4f, viewW - 40f)
            val top = (bubble.normTop * viewH - extraCoverY).coerceIn(4f, viewH - 30f)
            val right = (bubble.normRight * viewW + extraCoverX).coerceIn(left + 48f, viewW - 4f)
            val bottom = (bubble.normBottom * viewH + extraCoverY).coerceIn(top + 32f, viewH - 4f)

            val rect = RectF(left, top, right, bottom)
            val cornerRadius = (10f * density)

            // Force 100% solid opacity (0xFF alpha) on the sampled speech bubble background & text colors
            val solidBgColor = bubble.backgroundColorArgb or -0x1000000
            val solidTextColor = bubble.textColorArgb or -0x1000000

            bubbleFillPaint.color = solidBgColor
            bubbleFillPaint.alpha = 255
            textPaint.color = solidTextColor
            textPaint.alpha = 255
            bubbleBorderPaint.color = Color.argb(
                140,
                Color.red(solidTextColor),
                Color.green(solidTextColor),
                Color.blue(solidTextColor)
            )

            // Auto-fit English translation inside the bubble bounding box
            val padH = (6f * density).toInt()
            val padV = (4f * density).toInt()
            val availWidth = (rect.width() - padH * 2).toInt().coerceAtLeast(36)
            val availHeight = (rect.height() - padV * 2).toFloat().coerceAtLeast(20f)

            var fontSizePx = (16f * density)
            val minFontSizePx = (9.5f * density)
            var staticLayout: StaticLayout

            while (true) {
                textPaint.textSize = fontSizePx
                staticLayout = StaticLayout.Builder
                    .obtain(englishText, 0, englishText.length, textPaint, availWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(0f, 1.05f)
                    .setIncludePad(false)
                    .build()

                if (staticLayout.height <= availHeight || fontSizePx <= minFontSizePx) {
                    break
                }
                fontSizePx -= (1.0f * density)
            }

            val drawRect = if (staticLayout.height > rect.height() - padV) {
                val extra = (staticLayout.height - rect.height() + padV * 2) / 2f
                RectF(
                    rect.left,
                    (rect.top - extra).coerceAtLeast(4f),
                    rect.right,
                    (rect.bottom + extra).coerceAtMost(viewH - 4f)
                )
            } else {
                rect
            }

            // Record the exact normalized area covered by this drawn bubble so scroll detection ignores it
            drawnNormalizedRects.add(
                RectF(
                    (drawRect.left / viewW) - 0.02f,
                    (drawRect.top / viewH) - 0.02f,
                    (drawRect.right / viewW) + 0.02f,
                    (drawRect.bottom / viewH) + 0.02f
                )
            )

            // Draw solid opaque fill to cover all underlying foreign text
            canvas.drawRoundRect(drawRect, cornerRadius, cornerRadius, bubbleFillPaint)
            canvas.drawRoundRect(drawRect, cornerRadius, cornerRadius, bubbleBorderPaint)

            canvas.save()
            val textX = drawRect.left + padH
            val textY = drawRect.centerY() - (staticLayout.height / 2f)
            canvas.translate(textX, textY)
            staticLayout.draw(canvas)
            canvas.restore()
        }
    }
}

class ScreenOverlayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var windowManager: WindowManager? = null
    private var floatingRootView: LinearLayout? = null
    private var floatingPillParams: WindowManager.LayoutParams? = null
    private var bubbleOverlayView: ScreenBubbleOverlayView? = null
    private var scanButtonView: TextView? = null
    private var autoScanToggleView: TextView? = null
    private var clearButtonView: TextView? = null
    private var statusBadgeView: TextView? = null
    private var langPickerRowView: LinearLayout? = null
    private val langOptionViews = mutableMapOf<SourceLanguage, TextView>()

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var lastCapturedFrame: Bitmap? = null
    private var screenWidthPx: Int = 1080
    private var screenHeightPx: Int = 2400

    private var autoScrollMonitorJob: Job? = null
    private val autoScanDebouncer = com.example.ocr.AutoScanDebouncer(
        scope = serviceScope,
        debounceDelayMs = AUTO_SCAN_DEBOUNCE_DELAY_MS
    )
    private var isScanInProgress: Boolean = false
    private var lastFrameSignature: IntArray? = null
    private var lastTranslatedFrameSignature: IntArray? = null
    private var lastUserTouchRealtimeMs: Long = 0L
    private var lastScanCompletedRealtimeMs: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP_OVERLAY) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        startForegroundWithNotification(hasMediaProjection = resultCode == Activity.RESULT_OK && resultData != null)

        if (resultCode == Activity.RESULT_OK && resultData != null) {
            setupMediaProjection(resultCode, resultData)
        }

        if (Settings.canDrawOverlays(this)) {
            showOverlayWindows()
        }

        startContinuousScrollAutoScanLoop()
        OverlayStateHolder.setOverlayRunning(true)
        return START_STICKY
    }

    private fun startForegroundWithNotification(hasMediaProjection: Boolean) {
        val channelId = "komalens_overlay_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "KomaLens Screen Translator",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("KomaLens Screen Translator Active")
            .setContentText("Auto-scanning webtoon scroll or tap Translate anytime.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasMediaProjection) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun resolveRealScreenDimensions(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            bounds.width().coerceAtLeast(480) to bounds.height().coerceAtLeast(800)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels.coerceAtLeast(480) to metrics.heightPixels.coerceAtLeast(800)
        }
    }

    private fun setupMediaProjection(resultCode: Int, resultData: Intent) {
        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection?.stop()
            val projection = mpManager.getMediaProjection(resultCode, resultData)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    OverlayStateHolder.setProjectionActive(false)
                }
            }, Handler(Looper.getMainLooper()))

            val (w, h) = resolveRealScreenDimensions()
            screenWidthPx = w
            screenHeightPx = h
            val density = resources.displayMetrics.densityDpi

            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            virtualDisplay = projection?.createVirtualDisplay(
                "KomaLensScreenCapture",
                w,
                h,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )
            mediaProjection = projection
            imageReader = reader
            OverlayStateHolder.setProjectionActive(true)
            OverlayStateHolder.updateResults(
                emptyList(),
                "Screen translator ready (${OverlayStateHolder.activeLanguage.value.displayName} → English)"
            )
        } catch (e: Exception) {
            OverlayStateHolder.setProjectionActive(false)
            OverlayStateHolder.updateResults(
                emptyList(),
                "Failed to start screen capture: ${e.localizedMessage}"
            )
        }
    }

    private fun showOverlayWindows() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // 1. Full-screen non-touchable speech bubble replacement layer
        if (bubbleOverlayView == null) {
            val overlayParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }

            val canvasView = ScreenBubbleOverlayView(this).apply {
                visibility = View.GONE
            }
            try {
                wm.addView(canvasView, overlayParams)
                bubbleOverlayView = canvasView
            } catch (e: Exception) {
                // Ignore if unable to attach
            }
        }

        // 2. Compact Floating Control Pill with FLAG_WATCH_OUTSIDE_TOUCH to detect when user touches screen to scroll
        if (floatingRootView != null) return

        val pillParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 260
        }
        floatingPillParams = pillParams

        val outerRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#EE0D0F14"))
                setStroke(dp(2), Color.parseColor("#00E5FF"))
            }
        }

        val langPickerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(8), dp(6), dp(8), dp(6))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
            layoutParams = lp
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#F2121622"))
                setStroke(dp(1), Color.parseColor("#00E5FF"))
            }
        }
        langPickerRowView = langPickerRow

        // Dedicated button in the popup overlay to change Source Language (toggles quick KO / JA / ZH selector or cycles)
        val langBadge = TextView(this).apply {
            text = "🌐 ${OverlayStateHolder.activeLanguage.value.code}→EN ▾"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#22283A"))
                setStroke(dp(1), Color.parseColor("#5500E5FF"))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(4)
            }
            layoutParams = lp
            setOnClickListener {
                val isCurrentlyOpen = langPickerRow.visibility == View.VISIBLE
                langPickerRow.visibility = if (isCurrentlyOpen) View.GONE else View.VISIBLE
                updateLangSelectorStyles(OverlayStateHolder.activeLanguage.value)
            }
        }
        statusBadgeView = langBadge

        langOptionViews.clear()
        SourceLanguage.entries.forEachIndexed { index, lang ->
            val chip = TextView(this).apply {
                text = lang.displayName
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(10), dp(5), dp(10), dp(5))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index < SourceLanguage.entries.size - 1) {
                        marginEnd = dp(6)
                    }
                }
                layoutParams = lp
                setOnClickListener {
                    OverlayStateHolder.setLanguage(lang)
                    updateLangSelectorStyles(lang)
                    langPickerRow.visibility = View.GONE
                    autoScanDebouncer.cancel()
                    lastFrameSignature = null
                    triggerRealtimeScreenScan(isAutoScan = false)
                }
            }
            langOptionViews[lang] = chip
            langPickerRow.addView(chip)
        }
        updateLangSelectorStyles(OverlayStateHolder.activeLanguage.value)

        val autoBtn = TextView(this).apply {
            val isAuto = OverlayStateHolder.isAutoScanEnabled.value
            text = if (isAuto) "Auto:ON" else "Auto:OFF"
            setTextColor(if (isAuto) Color.parseColor("#00E676") else Color.parseColor("#9AA0B4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), dp(4), dp(8), dp(4))
            setOnClickListener {
                val nextState = !OverlayStateHolder.isAutoScanEnabled.value
                OverlayStateHolder.setAutoScanEnabled(nextState)
                updateAutoButtonStyle(this, nextState)
                autoScanDebouncer.cancel()
                lastFrameSignature = null
            }
        }
        autoScanToggleView = autoBtn

        val translateBtn = TextView(this).apply {
            text = "Translate"
            setTextColor(Color.parseColor("#0D0F14"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#00E5FF"))
            }
            setOnClickListener {
                langPickerRow.visibility = View.GONE
                autoScanDebouncer.cancel()
                triggerRealtimeScreenScan(isAutoScan = false)
            }
        }
        scanButtonView = translateBtn

        val clearBtn = TextView(this).apply {
            text = "Clear"
            visibility = View.GONE
            setTextColor(Color.parseColor("#F8F6F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(6), dp(6), dp(6))
            setOnClickListener {
                autoScanDebouncer.cancel()
                bubbleOverlayView?.clearBubbles()
                visibility = View.GONE
                lastFrameSignature = null
            }
        }
        clearButtonView = clearBtn

        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#FF5252"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(4), dp(6), dp(4))
            setOnClickListener {
                stopSelf()
            }
        }

        root.addView(langBadge)
        root.addView(autoBtn)
        root.addView(translateBtn)
        root.addView(clearBtn)
        root.addView(closeBtn)

        outerRoot.addView(root)
        outerRoot.addView(langPickerRow)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        val dragTouchListener = View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_OUTSIDE -> {
                    // User touched the comic/webtoon app underneath (e.g. to scroll)
                    lastUserTouchRealtimeMs = SystemClock.elapsedRealtime()
                    false
                }
                MotionEvent.ACTION_DOWN -> {
                    initialX = pillParams.x
                    initialY = pillParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                        isDragging = true
                        pillParams.x = initialX + dx
                        pillParams.y = initialY + dy
                        wm.updateViewLayout(outerRoot, pillParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        view.performClick()
                    }
                    true
                }
                else -> isDragging
            }
        }

        outerRoot.setOnTouchListener(dragTouchListener)
        root.setOnTouchListener(dragTouchListener)
        langBadge.setOnTouchListener(dragTouchListener)
        autoBtn.setOnTouchListener(dragTouchListener)
        translateBtn.setOnTouchListener(dragTouchListener)
        clearBtn.setOnTouchListener(dragTouchListener)
        closeBtn.setOnTouchListener(dragTouchListener)

        try {
            wm.addView(outerRoot, pillParams)
            floatingRootView = outerRoot
        } catch (e: Exception) {
            // Ignore if window token invalid
        }
    }

    private fun updateLangSelectorStyles(activeLang: SourceLanguage) {
        statusBadgeView?.text = "🌐 ${activeLang.code}→EN ▾"
        langOptionViews.forEach { (lang, view) ->
            val isSelected = lang == activeLang
            view.setTextColor(if (isSelected) Color.parseColor("#0D0F14") else Color.parseColor("#F8F6F0"))
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(if (isSelected) Color.parseColor("#00E5FF") else Color.parseColor("#22283A"))
            }
        }
    }

    private fun updateAutoButtonStyle(btn: TextView, enabled: Boolean) {
        btn.text = if (enabled) "Auto:ON" else "Auto:OFF"
        btn.setTextColor(if (enabled) Color.parseColor("#00E676") else Color.parseColor("#9AA0B4"))
    }

    /**
     * Continuously monitors the screen for scroll movement and uses a 500ms debounce delay
     * (`AUTO_SCAN_DEBOUNCE_DELAY_MS = 500L`) before triggering a new OCR and translation cycle.
     * Every scroll frame resets the 500ms timer, preventing redundant API calls and UI flickering.
     */
    private fun startContinuousScrollAutoScanLoop() {
        autoScrollMonitorJob?.cancel()
        autoScrollMonitorJob = serviceScope.launch {
            while (isActive) {
                delay(200)
                val autoEnabled = OverlayStateHolder.isAutoScanEnabled.value
                autoScanToggleView?.let { updateAutoButtonStyle(it, autoEnabled) }
                updateLangSelectorStyles(OverlayStateHolder.activeLanguage.value)

                if (!autoEnabled || isScanInProgress || !OverlayStateHolder.isMediaProjectionActive.value) {
                    continue
                }

                val now = SystemClock.elapsedRealtime()
                // Cooldown immediately after a scan completes so overlay rendering never triggers a false diff
                if (now - lastScanCompletedRealtimeMs < 900L) {
                    continue
                }

                val latestFrame = captureFrameFromProjection() ?: continue
                lastCapturedFrame = latestFrame

                val excludedRects = buildExcludedNormalizedRects()
                val currentSig = computeScrollSignature(
                    bitmap = latestFrame,
                    excludedRects = excludedRects
                )

                val prevSig = lastFrameSignature
                if (prevSig == null) {
                    // Establish stationary baseline AFTER overlays are rendered — do NOT trigger a scan
                    lastFrameSignature = currentSig
                    continue
                }

                val diff = compareScrollSignatures(prevSig, currentSig)
                lastFrameSignature = currentSig
                val userTouchedRecently = (now - lastUserTouchRealtimeMs) < 5000L

                val isGenuineScrollMotion = (userTouchedRecently && diff >= 0.12f) || (diff >= 0.22f)

                if (isGenuineScrollMotion) {
                    // Hide stale overlay bubbles once at the start of scroll motion
                    if (bubbleOverlayView?.visibility == View.VISIBLE) {
                        bubbleOverlayView?.clearBubbles()
                        clearButtonView?.visibility = View.GONE
                    }
                    // Debounce by 500ms: any further scroll movement within 500ms cancels & restarts this timer
                    autoScanDebouncer.onScrollOrFrameChanged {
                        if (!isScanInProgress && OverlayStateHolder.isAutoScanEnabled.value) {
                            val settledFrame = captureFrameFromProjection() ?: lastCapturedFrame
                            if (settledFrame != null) {
                                lastCapturedFrame = settledFrame
                                val settledSig = computeScrollSignature(
                                    bitmap = settledFrame,
                                    excludedRects = buildExcludedNormalizedRects()
                                )
                                val prevTranslatedSig = lastTranslatedFrameSignature
                                // Skip redundant OCR & API call if screen settled back to the same already-translated frame
                                if (prevTranslatedSig == null ||
                                    compareScrollSignatures(prevTranslatedSig, settledSig) >= 0.08f
                                ) {
                                    triggerRealtimeScreenScan(isAutoScan = true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun buildExcludedNormalizedRects(): List<RectF> {
        val rects = mutableListOf<RectF>()
        bubbleOverlayView?.currentDrawnNormalizedRects()?.let { rects.addAll(it) }

        val pill = floatingRootView
        val params = floatingPillParams
        val w = screenWidthPx.toFloat().coerceAtLeast(1f)
        val h = screenHeightPx.toFloat().coerceAtLeast(1f)
        if (pill != null && params != null && pill.width > 0 && pill.height > 0) {
            rects.add(
                RectF(
                    (params.x.toFloat() / w) - 0.03f,
                    (params.y.toFloat() / h) - 0.03f,
                    ((params.x + pill.width).toFloat() / w) + 0.03f,
                    ((params.y + pill.height).toFloat() / h) + 0.03f
                )
            )
        }
        return rects
    }

    private fun computeScrollSignature(
        bitmap: Bitmap,
        excludedRects: List<RectF>
    ): IntArray {
        val cols = 12
        val rows = 16
        val sig = IntArray(cols * rows)
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        var idx = 0

        for (r in 0 until rows) {
            val normY = 0.14f + (0.76f * r) / (rows - 1)
            val py = (normY * h).toInt().coerceIn(0, h - 1)
            for (c in 0 until cols) {
                val normX = 0.08f + (0.84f * c) / (cols - 1)
                val px = (normX * w).toInt().coerceIn(0, w - 1)

                val isExcluded = excludedRects.any { rect ->
                    normX >= rect.left && normX <= rect.right &&
                        normY >= rect.top && normY <= rect.bottom
                }

                if (isExcluded) {
                    sig[idx++] = -1
                } else {
                    val color = bitmap.getPixel(px, py)
                    val lum = (Color.red(color) * 3 + Color.green(color) * 6 + Color.blue(color)) / 10
                    sig[idx++] = lum
                }
            }
        }
        return sig
    }

    private fun compareScrollSignatures(a: IntArray, b: IntArray): Float {
        if (a.size != b.size) return 1f
        var validCount = 0
        var changedCount = 0
        for (i in a.indices) {
            val va = a[i]
            val vb = b[i]
            if (va >= 0 && vb >= 0) {
                validCount++
                if (abs(va - vb) > 22) {
                    changedCount++
                }
            }
        }
        if (validCount < 16) return 0f
        return changedCount.toFloat() / validCount.toFloat()
    }

    private fun getTopStatusBarExclusionRatio(): Float {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarPx = if (resId > 0) resources.getDimensionPixelSize(resId) else dp(32)
        val safeTopPx = statusBarPx + dp(28)
        return (safeTopPx.toFloat() / screenHeightPx.coerceAtLeast(800)).coerceIn(0.08f, 0.14f)
    }

    private fun triggerRealtimeScreenScan(isAutoScan: Boolean = false) {
        if (isScanInProgress) return
        isScanInProgress = true
        autoScanDebouncer.cancel()

        val lang = OverlayStateHolder.activeLanguage.value
        updateLangSelectorStyles(lang)
        langPickerRowView?.visibility = View.GONE
        scanButtonView?.text = "..."

        serviceScope.launch {
            try {
                bubbleOverlayView?.clearBubbles()
                floatingRootView?.visibility = View.INVISIBLE
                delay(150)

                val capturedBitmap = captureFrameFromProjection() ?: lastCapturedFrame
                if (capturedBitmap != null) {
                    lastCapturedFrame = capturedBitmap
                }

                floatingRootView?.visibility = View.VISIBLE

                if (capturedBitmap == null) {
                    scanButtonView?.text = "Translate"
                    OverlayStateHolder.updateResults(
                        emptyList(),
                        "Screen capture permission needed — open KomaLens and tap Start"
                    )
                    return@launch
                }

                val topIgnoreRatio = getTopStatusBarExclusionRatio()

                val bubbles = MangaOcrPipeline.detectAndTranslate(
                    bitmap = capturedBitmap,
                    language = lang,
                    glossary = OverlayStateHolder.cachedGlossary,
                    fallbackRegions = emptyList(),
                    topIgnoreRatio = topIgnoreRatio,
                    bottomIgnoreRatio = 0.94f
                )

                scanButtonView?.text = "Translate"

                if (bubbles.isNotEmpty()) {
                    bubbleOverlayView?.setDetectedBubbles(bubbles)
                    clearButtonView?.visibility = View.VISIBLE
                    val modePrefix = if (isAutoScan) "Auto-translated" else "Replaced"
                    OverlayStateHolder.updateResults(
                        bubbles,
                        "$modePrefix ${bubbles.size} speech bubbles in English"
                    )
                } else {
                    bubbleOverlayView?.clearBubbles()
                    clearButtonView?.visibility = View.GONE
                    if (!isAutoScan) {
                        OverlayStateHolder.updateResults(
                            emptyList(),
                            "No ${lang.displayName} text found on screen (tap ${lang.code}→EN to switch language)"
                        )
                    }
                }

                // Wait for Android SurfaceFlinger to finish compositing the newly drawn bubbles & pill,
                // then drain the frame buffer and save the post-translation signature so it never self-triggers
                delay(280)
                captureFrameFromProjection()
                lastTranslatedFrameSignature = computeScrollSignature(
                    bitmap = capturedBitmap,
                    excludedRects = buildExcludedNormalizedRects()
                )
                lastFrameSignature = null
                lastScanCompletedRealtimeMs = SystemClock.elapsedRealtime()
            } finally {
                isScanInProgress = false
            }
        }
    }

    private fun captureFrameFromProjection(): Bitmap? {
        val reader = imageReader ?: return null
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return null

        return try {
            val plane = image.planes.firstOrNull() ?: return null
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val rawBitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            rawBitmap.copyPixelsFromBuffer(buffer)
            Bitmap.createBitmap(rawBitmap, 0, 0, image.width, image.height)
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        autoScanDebouncer.cancel()
        autoScrollMonitorJob?.cancel()
        bubbleOverlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                // Ignore
            }
        }
        bubbleOverlayView = null

        floatingRootView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                // Ignore
            }
        }
        floatingRootView = null

        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        OverlayStateHolder.setOverlayRunning(false)
        OverlayStateHolder.setProjectionActive(false)
        serviceScope.cancel()
    }

    companion object {
        const val ACTION_START_OVERLAY = "com.example.overlay.START_OVERLAY"
        const val ACTION_STOP_OVERLAY = "com.example.overlay.STOP_OVERLAY"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val AUTO_SCAN_DEBOUNCE_DELAY_MS = 500L
        private const val NOTIFICATION_ID = 4012
    }
}
