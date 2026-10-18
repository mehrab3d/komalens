package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.DetectedBubble
import com.example.data.model.OverlayDisplayMode
import com.example.data.model.SourceLanguage
import com.example.ocr.SampleComicGenerator
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.MangaMagenta
import com.example.ui.theme.PaperCream
import com.example.ui.theme.SumiInk
import com.example.viewmodel.KomaLensViewModel
import com.example.viewmodel.ReaderUiState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderScreen(
    readerState: ReaderUiState,
    activeBitmap: Bitmap,
    viewModel: KomaLensViewModel
) {
    val context = LocalContext.current
    var showBubbleInspectorSheet by remember { mutableStateOf(false) }
    var showWebtoonUrlDialog by remember { mutableStateOf(false) }
    var webtoonUrlInput by remember(readerState.webtoonUrl) { mutableStateOf(readerState.webtoonUrl) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Zero-permission Android Photo Picker for loading local manga/manhwa chapter pages
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            try {
                val rawBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.isMutableRequired = true
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                        .copy(Bitmap.Config.ARGB_8888, true)
                }
                viewModel.setCustomImageBitmap(rawBitmap, "Local Chapter Page")
            } catch (e: Exception) {
                // Ignore decoding failure
            }
        }
    }

    if (showBubbleInspectorSheet && readerState.selectedBubble != null) {
        BackHandler { showBubbleInspectorSheet = false }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("reader_screen_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Source Language Selector Row (Japanese Manga, Korean Manhwa, Chinese Manhua)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SOURCE SCRIPT & OCR ENGINE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Surface(
                            color = if (readerState.isGeminiConfigured) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (readerState.isGeminiConfigured) {
                                    "ML Kit + Gemini 3.5 Flash"
                                } else {
                                    "ML Kit On-Device + Lexicon"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SourceLanguage.entries.forEach { lang ->
                            val selected = readerState.selectedLanguage == lang
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.selectLanguage(lang) },
                                label = {
                                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                        Text(
                                            text = "${lang.code} • ${lang.displayName}",
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                        Text(
                                            text = lang.comicType,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 10.sp
                                        )
                                    }
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("lang_chip_${lang.code.lowercase()}")
                            )
                        }
                    }
                }
            }
        }

        // 2. Sample Raw Chapter Switcher + Local Image Picker + Webtoon Browser Toggle
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(SampleComicGenerator.samplePages) { sample ->
                        val isSelected = !readerState.isWebtoonMode &&
                            readerState.customBitmap == null &&
                            readerState.activeSamplePage.id == sample.id
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.selectSamplePage(sample) },
                            label = { Text("${sample.language.code}: ${sample.title}") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = sample.title,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            modifier = Modifier.testTag("sample_page_${sample.id}")
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("pick_local_image_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = "Load local image",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Load Local Page", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    OutlinedButton(
                        onClick = {
                            if (readerState.isWebtoonMode) {
                                viewModel.setWebtoonMode(false)
                            } else {
                                showWebtoonUrlDialog = true
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("toggle_webtoon_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = "Webtoon browser",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (readerState.isWebtoonMode) "Exit WebView" else "Webtoon Browser",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // 3. Portion-of-Screen Region Crop Controls & Display Mode Bar
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            OverlayDisplayMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = readerState.displayMode == mode,
                                    onClick = { viewModel.setDisplayMode(mode) },
                                    label = {
                                        Text(
                                            text = mode.label,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    modifier = Modifier.testTag("display_mode_${mode.name.lowercase()}")
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                viewModel.toggleCropRegionMode(!readerState.isCropRegionEnabled)
                            },
                            modifier = Modifier.testTag("crop_region_toggle_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CropFree,
                                contentDescription = "Toggle screen portion crop selector",
                                tint = if (readerState.isCropRegionEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }

                    AnimatedVisibility(visible = readerState.isCropRegionEnabled) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            Text(
                                text = "Screen Portion Crop Area (Vertical Band: ${(readerState.cropRegion.top * 100).roundToInt()}% – ${(readerState.cropRegion.bottom * 100).roundToInt()}%)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            RangeSlider(
                                value = readerState.cropRegion.top..readerState.cropRegion.bottom,
                                onValueChange = { range ->
                                    viewModel.updateCropRegion(
                                        left = readerState.cropRegion.left,
                                        top = range.start,
                                        right = readerState.cropRegion.right,
                                        bottom = range.endInclusive
                                    )
                                },
                                valueRange = 0.02f..0.98f,
                                modifier = Modifier.testTag("crop_vertical_range_slider")
                            )
                        }
                    }
                }
            }
        }

        // 4. Primary Action Bar: Scan & Translate Page + Save to Cloud History
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        if (readerState.isWebtoonMode && webViewRef != null) {
                            val wv = webViewRef!!
                            if (wv.width > 0 && wv.height > 0) {
                                val bmp = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(bmp)
                                wv.draw(canvas)
                                viewModel.setCustomImageBitmap(bmp, "Webtoon Frame Capture")
                            }
                        } else {
                            viewModel.scanCurrentPage(autoSaveToHistory = false)
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .weight(1.3f)
                        .height(50.dp)
                        .testTag("scan_translate_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DocumentScanner,
                        contentDescription = "Scan and translate",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (readerState.isScanning) "Scanning OCR..." else "Scan & Translate",
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                OutlinedButton(
                    onClick = { viewModel.saveCurrentScanToHistory() },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(0.9f)
                        .height(50.dp)
                        .testTag("save_history_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.BookmarkAdd,
                        contentDescription = "Save to cloud history",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save Scan", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // Status Toast / Banner
        if (readerState.statusBannerMessage != null) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = readerState.statusBannerMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.clearStatusBanner() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss status banner",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // 5. Interactive Comic Page & Speech Bubble Translation Canvas (or Webtoon Browser with Auto-Scan on Scroll)
        item {
            if (readerState.isWebtoonMode) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(500.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    webViewClient = WebViewClient()
                                    var scrollDebounceRunnable: Runnable? = null
                                    setOnScrollChangeListener { v, _, scrollY, _, oldScrollY ->
                                        if (kotlin.math.abs(scrollY - oldScrollY) > 18) {
                                            scrollDebounceRunnable?.let { v.removeCallbacks(it) }
                                            val runnable = Runnable {
                                                if (v.width > 0 && v.height > 0) {
                                                    val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                                                    val canvas = android.graphics.Canvas(bmp)
                                                    v.draw(canvas)
                                                    viewModel.setCustomImageBitmap(bmp, "Webtoon Auto-Scroll Capture")
                                                }
                                            }
                                            scrollDebounceRunnable = runnable
                                            v.postDelayed(runnable, com.example.ocr.MangaOcrPipeline.AUTO_SCAN_DEBOUNCE_DELAY_MS)
                                        }
                                    }
                                    loadUrl(readerState.webtoonUrl)
                                    webViewRef = this
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else {
                ComicPageTranslationCanvas(
                    bitmap = activeBitmap,
                    bubbles = readerState.detectedBubbles,
                    selectedBubble = readerState.selectedBubble,
                    displayMode = readerState.displayMode,
                    isScanning = readerState.isScanning,
                    isCropEnabled = readerState.isCropRegionEnabled,
                    cropRegion = readerState.cropRegion,
                    onBubbleTapped = { bubble ->
                        viewModel.selectBubble(bubble)
                        showBubbleInspectorSheet = true
                    }
                )
            }
        }

        // 6. Detected Speech Bubbles Interactive List (Always visible below canvas or in Split Mode)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DETECTED SPEECH BUBBLES (${readerState.detectedBubbles.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Tap any bubble for Romaji/Pinyin & breakdown",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(readerState.detectedBubbles, key = { it.id }) { bubble ->
            val isSelected = readerState.selectedBubble?.id == bubble.id
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = if (isSelected) 1.5.dp else 0.5.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                        },
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clickable {
                        viewModel.selectBubble(bubble)
                        showBubbleInspectorSheet = true
                    }
                    .testTag("detected_bubble_card_${bubble.index}")
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "${bubble.index}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = bubble.rawText,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = "Inspect bubble breakdown",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    if (bubble.romanization.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = bubble.romanization,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = bubble.activeTranslation,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    // Bubble Breakdown Modal Bottom Sheet
    if (showBubbleInspectorSheet && readerState.selectedBubble != null) {
        val bubble = readerState.selectedBubble
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var newSourceTerm by remember(bubble.id) { mutableStateOf(bubble.rawText.take(12)) }
        var newTargetTerm by remember(bubble.id) { mutableStateOf("") }

        ModalBottomSheet(
            onDismissRequest = { showBubbleInspectorSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "BUBBLE #${bubble.index} LINGUISTIC BREAKDOWN",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = bubble.engineUsed,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "RAW DETECTED SCRIPT (${bubble.sourceLanguage})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = bubble.rawText,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (bubble.romanization.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = bubble.romanization,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "LOCALIZED BUBBLE DIALOGUE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = bubble.activeTranslation,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (bubble.mlKitTranslation.isNotBlank() &&
                            bubble.mlKitTranslation != bubble.activeTranslation
                        ) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Baseline ML Kit Translate: ${bubble.mlKitTranslation}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (bubble.literalBreakdown.isNotBlank() || bubble.culturalNote.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (bubble.literalBreakdown.isNotBlank()) {
                            Text(
                                text = "Word & Grammar Breakdown:",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = bubble.literalBreakdown,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (bubble.culturalNote.isNotBlank()) {
                            Text(
                                text = "Manga/Manhwa Context Note:",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = bubble.culturalNote,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Quick "+ Add Term to Glossary" directly from inspected bubble
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "PIN RECURRING NAME / TERM TO CLOUD GLOSSARY",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = newSourceTerm,
                                onValueChange = { newSourceTerm = it },
                                label = { Text("Raw CJK Term") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = newTargetTerm,
                                onValueChange = { newTargetTerm = it },
                                label = { Text("English Term") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (newSourceTerm.isNotBlank() && newTargetTerm.isNotBlank()) {
                                    viewModel.addGlossaryTerm(
                                        sourceTerm = newSourceTerm,
                                        targetTerm = newTargetTerm,
                                        sourceLanguage = bubble.sourceLanguage,
                                        seriesTitle = readerState.activeSamplePage.title,
                                        category = "Character",
                                        notes = "Pinned from Bubble #${bubble.index}"
                                    )
                                    showBubbleInspectorSheet = false
                                }
                            },
                            enabled = newSourceTerm.isNotBlank() && newTargetTerm.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pin_bubble_term_to_glossary_button")
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = "Pin term")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save Term to Glossary")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showWebtoonUrlDialog) {
        ModalBottomSheet(
            onDismissRequest = { showWebtoonUrlDialog = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Open Raw Webtoon / Manga Portal",
                    style = MaterialTheme.typography.titleLarge
                )
                OutlinedTextField(
                    value = webtoonUrlInput,
                    onValueChange = { webtoonUrlInput = it },
                    label = { Text("Webtoon or Raw Chapter URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        viewModel.setWebtoonMode(true, webtoonUrlInput.trim())
                        showWebtoonUrlDialog = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Launch Integrated Reader")
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ComicPageTranslationCanvas(
    bitmap: Bitmap,
    bubbles: List<DetectedBubble>,
    selectedBubble: DetectedBubble?,
    displayMode: OverlayDisplayMode,
    isScanning: Boolean,
    isCropEnabled: Boolean,
    cropRegion: android.graphics.RectF,
    onBubbleTapped: (DetectedBubble) -> Unit
) {
    val aspectRatio = remember(bitmap) {
        (bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat()).coerceIn(0.55f, 1.4f)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "laser_scan")
    val laserProgress by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "laser_y"
    )

    Card(
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .border(
                width = 1.5.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                shape = RoundedCornerShape(22.dp)
            )
            .testTag("comic_translation_canvas")
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val canvasWidthPx = constraints.maxWidth.toFloat()
            val canvasHeightPx = constraints.maxHeight.toFloat()

            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Active manga or manhwa page",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )

            // Draw bounding boxes, crop region frame, and laser sweep
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (isCropEnabled) {
                    val cropLeft = cropRegion.left * size.width
                    val cropTop = cropRegion.top * size.height
                    val cropW = (cropRegion.right - cropRegion.left) * size.width
                    val cropH = (cropRegion.bottom - cropRegion.top) * size.height
                    drawRect(
                        color = CyberCyan,
                        topLeft = Offset(cropLeft, cropTop),
                        size = Size(cropW, cropH),
                        style = Stroke(width = 5f)
                    )
                }

                if (isScanning) {
                    val yPos = size.height * laserProgress
                    drawLine(
                        color = CyberCyan,
                        start = Offset(0f, yPos),
                        end = Offset(size.width, yPos),
                        strokeWidth = 6f
                    )
                }
            }

            // Render interactive Speech Bubble Overlays directly inside detected bubble coordinates!
            bubbles.forEach { bubble ->
                val isSelected = selectedBubble?.id == bubble.id
                val leftPx = (bubble.normLeft * canvasWidthPx).roundToInt()
                val topPx = (bubble.normTop * canvasHeightPx).roundToInt()
                val widthDp = ((bubble.normRight - bubble.normLeft) * maxWidth.value).coerceAtLeast(64f).dp
                val heightDp = ((bubble.normBottom - bubble.normTop) * maxHeight.value).coerceAtLeast(42f).dp

                val matchedBgColor = Color(bubble.backgroundColorArgb)
                val matchedTextColor = Color(bubble.textColorArgb)

                Box(
                    modifier = Modifier
                        .offset { IntOffset(leftPx, topPx) }
                        .size(width = widthDp, height = heightDp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            when (displayMode) {
                                OverlayDisplayMode.TRANSLATED -> matchedBgColor
                                OverlayDisplayMode.RAW_PEEK -> Color(0x3300E5FF)
                                OverlayDisplayMode.SPLIT_LIST -> Color(0x550D0F14)
                            }
                        )
                        .border(
                            width = if (isSelected) 2.5.dp else 1.5.dp,
                            color = if (isSelected) MangaMagenta else matchedTextColor.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { onBubbleTapped(bubble) }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .testTag("canvas_bubble_overlay_${bubble.index}"),
                    contentAlignment = Alignment.Center
                ) {
                    if (displayMode == OverlayDisplayMode.TRANSLATED) {
                        Text(
                            text = bubble.activeTranslation,
                            color = matchedTextColor,
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Surface(
                            color = SumiInk.copy(alpha = 0.85f),
                            shape = CircleShape,
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            Text(
                                text = "#${bubble.index}",
                                color = CyberCyan,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
