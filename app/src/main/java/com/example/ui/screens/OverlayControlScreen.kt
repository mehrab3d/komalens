package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.SourceLanguage
import com.example.ocr.OfflineModelState
import com.example.overlay.OverlayStateHolder
import com.example.overlay.ScreenOverlayService
import com.example.viewmodel.KomaLensViewModel

@Composable
fun OverlayControlScreen(
    viewModel: KomaLensViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val isOverlayRunning by OverlayStateHolder.isOverlayRunning.collectAsStateWithLifecycle()
    val isAutoScanEnabled by OverlayStateHolder.isAutoScanEnabled.collectAsStateWithLifecycle()
    val activeLang by OverlayStateHolder.activeLanguage.collectAsStateWithLifecycle()
    val offlineModelStates by OverlayStateHolder.offlineModelStates.collectAsStateWithLifecycle()
    val isPreDownloadingModels by OverlayStateHolder.isPreDownloadingModels.collectAsStateWithLifecycle()
    val lastStatus by OverlayStateHolder.lastCaptureStatus.collectAsStateWithLifecycle()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canDrawOverlays = Settings.canDrawOverlays(context)
                viewModel.refreshOfflineModelStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val overlaySettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        canDrawOverlays = Settings.canDrawOverlays(context)
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val serviceIntent = Intent(context, ScreenOverlayService::class.java).apply {
            action = ScreenOverlayService.ACTION_START_OVERLAY
            putExtra(ScreenOverlayService.EXTRA_RESULT_CODE, result.resultCode)
            if (result.data != null) {
                putExtra(ScreenOverlayService.EXTRA_RESULT_DATA, result.data)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    val activeModelState = offlineModelStates[activeLang] ?: OfflineModelState.NOT_DOWNLOADED
    val readyCount = offlineModelStates.values.count { it == OfflineModelState.READY }
    val allModelsReady = readyCount == SourceLanguage.entries.size

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .testTag("overlay_control_screen"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 400.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = if (isOverlayRunning) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.size(68.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = "Screen Translator",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (isOverlayRunning) "Translator Active" else "Screen Translator",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = lastStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            // Minimal Settings Card: Language + Auto-Scan Switch + Offline Translation Model Pre-Download
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Translate to English from:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SourceLanguage.entries.forEach { lang ->
                            FilterChip(
                                selected = activeLang == lang,
                                onClick = { viewModel.selectLanguage(lang) },
                                label = {
                                    Text(
                                        text = lang.displayName,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Center
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("overlay_lang_${lang.code.lowercase()}")
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Auto-Scan While Scrolling",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Switch(
                            checked = isAutoScanEnabled,
                            onCheckedChange = { OverlayStateHolder.setAutoScanEnabled(it) },
                            modifier = Modifier.testTag("auto_scan_scroll_switch")
                        )
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Offline Translation Models",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = when {
                                    isPreDownloadingModels -> "Downloading ${activeLang.displayName} & CJK packs..."
                                    allModelsReady -> "KO, JA, ZH ready for offline use ($readyCount/3)"
                                    activeModelState == OfflineModelState.READY -> "${activeLang.displayName} ready ($readyCount/3 downloaded)"
                                    else -> "Pre-download KO, JA, ZH for instant speed"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        OutlinedButton(
                            onClick = { viewModel.preDownloadOfflineModels(activeLang) },
                            enabled = !isPreDownloadingModels && !allModelsReady,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .height(38.dp)
                                .testTag("predownload_offline_model_button")
                        ) {
                            when {
                                isPreDownloadingModels -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Saving",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                allModelsReady -> {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Offline models ready",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Ready",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                else -> {
                                    Icon(
                                        imageVector = Icons.Default.CloudDownload,
                                        contentDescription = "Pre-download offline models",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Download",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Permission button only if not granted yet
            if (!canDrawOverlays) {
                OutlinedButton(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        overlaySettingsLauncher.launch(intent)
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("grant_overlay_permission_button")
                ) {
                    Text("Allow Overlay Permission")
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Overlay permission ready",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Overlay permission ready",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Single Primary Start / Stop Button
            if (!isOverlayRunning) {
                Button(
                    onClick = {
                        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
                    },
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("start_floating_overlay_button")
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Start")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Start Screen Translator",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Button(
                    onClick = {
                        val stopIntent = Intent(context, ScreenOverlayService::class.java).apply {
                            action = ScreenOverlayService.ACTION_STOP_OVERLAY
                        }
                        context.startService(stopIntent)
                    },
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("stop_floating_overlay_button")
                ) {
                    Icon(imageVector = Icons.Default.Stop, contentDescription = "Stop")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Stop Screen Translator",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
