package com.stemstudio.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stemstudio.app.ui.theme.*
import java.util.Locale

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.onTrackSelected(it) }
    }

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearToast()
        }
    }

    Scaffold(
        containerColor = StudioBg,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "STEMSTUDIO AI",
                        color = NeonCyan,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp
                    )
                    Text(
                        text = "HTDemucs-6s • 100% Local ARM64",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = NeonLime.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, NeonLime.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "OFFLINE ENGINE",
                        color = NeonLime,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = StudioSurface,
                border = BorderStroke(1.dp, Color(0xFF262B3D))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(NeonCyan.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.AudioFile, contentDescription = null, tint = NeonCyan)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.selectedFileName ?: "Трек не выбран",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = state.statusText,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { audioPicker.launch(arrayOf("audio/*")) },
                            enabled = !state.isProcessing,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f))
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Выбрать трек")
                        }

                        Button(
                            onClick = { viewModel.startSeparation() },
                            enabled = state.selectedUri != null && !state.isProcessing,
                            modifier = Modifier.weight(1.25f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonMagenta,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (state.isProcessing) "Обработка..." else "Разделить на 6 стемов",
                                maxLines = 1,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (state.isProcessing || state.progressPercent > 0) {
                        val animatedProgress by animateFloatAsState(
                            targetValue = state.progressPercent / 100f,
                            label = "sep_progress"
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Прогресс AI-движка", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                Text(
                                    "${state.progressPercent}%",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeonCyan
                                )
                            }
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(7.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = NeonCyan,
                                trackColor = Color(0xFF23283A)
                            )
                        }
                    }
                }
            }

            TerminalPanel(
                logs = state.logs,
                expanded = state.terminalExpanded,
                onToggle = { viewModel.toggleTerminal() },
                onCopy = {
                    clipboard.setText(AnnotatedString(state.logs.joinToString("\\n")))
                }
            )

            if (state.stemsReady) {
                MasterTransportBar(
                    isPlaying = state.isPlaying,
                    currentMs = state.currentPositionMs,
                    durationMs = state.durationMs,
                    isExporting = state.isExporting,
                    onPlayPause = { viewModel.togglePlayPause() },
                    onSeek = { viewModel.seekTo(it) },
                    onExportMix = { viewModel.exportCustomMix() }
                )

                Text(
                    text = "МИКШЕР СТЕМОВ (6 КАНАЛОВ)",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp
                )

                state.stems.forEach { stem ->
                    StemCard(
                        stem = stem,
                        onVolumeChange = { viewModel.onStemVolumeChange(stem.id, it) },
                        onMuteToggle = { viewModel.onStemMuteToggle(stem.id) },
                        onSoloToggle = { viewModel.onStemSoloToggle(stem.id) },
                        onSaveStem = { viewModel.exportSingleStem(stem) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TerminalPanel(
    logs: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size, expanded) {
        if (expanded && logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = TerminalBg,
        border = BorderStroke(1.dp, Color(0xFF1E293B))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .background(Color(0xFF0E121B))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Terminal,
                        contentDescription = null,
                        tint = NeonLime,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Встроенный терминал (${logs.size})",
                        color = Color(0xFFE2E8F0),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCopy, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Копировать логи",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = Color(0xFF94A3B8)
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(logs) { line ->
                        val lineColor = when {
                            line.contains("[ERROR]") || line.contains("[FATAL]") -> Color(0xFFFF5555)
                            line.contains("[DONE]") || line.contains("[SAVE]") -> NeonLime
                            line.contains("[AI]") || line.contains("[ONNX]") -> NeonCyan
                            line.contains("[NET]") -> NeonAmber
                            else -> Color(0xFFB8C4D9)
                        }
                        Text(
                            text = line,
                            color = lineColor,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MasterTransportBar(
    isPlaying: Boolean,
    currentMs: Long,
    durationMs: Long,
    isExporting: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onExportMix: () -> Unit
) {
    val fraction = if (durationMs > 0) (currentMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = StudioSurfaceElevated,
        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(NeonCyan, NeonViolet)))
                            .clickable { onPlayPause() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color(0xFF0A0B0F),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Column {
                        Text("Мастер-шина", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "${formatTime(currentMs)} / ${formatTime(durationMs)}",
                            color = NeonCyan,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }

                FilledTonalButton(
                    onClick = onExportMix,
                    enabled = !isExporting,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = NeonLime.copy(alpha = 0.16f),
                        contentColor = NeonLime
                    )
                ) {
                    Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isExporting) "Рендер..." else "Экспорт микса", fontSize = 12.sp)
                }
            }

            Slider(
                value = fraction,
                onValueChange = onSeek,
                colors = SliderDefaults.colors(
                    thumbColor = NeonCyan,
                    activeTrackColor = NeonCyan,
                    inactiveTrackColor = Color(0xFF2B3147)
                )
            )
        }
    }
}

@Composable
private fun StemCard(
    stem: StemTrackUi,
    onVolumeChange: (Float) -> Unit,
    onMuteToggle: () -> Unit,
    onSoloToggle: () -> Unit,
    onSaveStem: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = StudioSurface,
        border = BorderStroke(
            width = 1.dp,
            color = if (stem.isSolo) stem.accent else Color(0xFF252A3C)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (stem.isMuted) Color(0xFF475569) else stem.accent)
                    )
                    Text(
                        text = stem.title,
                        color = if (stem.isMuted) Color(0xFF64748B) else Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ToggleBadge(
                        label = "M",
                        active = stem.isMuted,
                        activeColor = Color(0xFFFF4D4D),
                        onClick = onMuteToggle
                    )
                    ToggleBadge(
                        label = "S",
                        active = stem.isSolo,
                        activeColor = NeonAmber,
                        onClick = onSoloToggle
                    )
                    IconButton(onClick = onSaveStem, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "Сохранить стем",
                            tint = stem.accent,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Slider(
                    value = stem.volume,
                    onValueChange = onVolumeChange,
                    valueRange = 0f..1.5f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = stem.accent,
                        activeTrackColor = stem.accent,
                        inactiveTrackColor = Color(0xFF262B3D)
                    )
                )
                Text(
                    text = "${(stem.volume * 100).toInt()}%",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(36.dp)
                )
            }
        }
    }
}

@Composable
private fun ToggleBadge(
    label: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) activeColor.copy(alpha = 0.22f) else Color(0xFF1F2434))
            .border(
                width = 1.dp,
                color = if (active) activeColor else Color(0xFF333B52),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (active) activeColor else Color(0xFF94A3B8),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format(Locale.US, "%02d:%02d", min, sec)
}
