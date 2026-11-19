package com.stemstudio.app.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chaquo.python.Python
import com.stemstudio.app.audio.AudioDecoder
import com.stemstudio.app.audio.MultiTrackPlayer
import com.stemstudio.app.engine.OnnxInferenceBridge
import com.stemstudio.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StemTrackUi(
    val id: String,
    val title: String,
    val accent: Color,
    val volume: Float = 1.0f,
    val isMuted: Boolean = false,
    val isSolo: Boolean = false,
    val file: File? = null
)

data class StudioUiState(
    val selectedUri: Uri? = null,
    val selectedFileName: String? = null,
    val isProcessing: Boolean = false,
    val progressPercent: Int = 0,
    val statusText: String = "Выберите аудиофайл (MP3, WAV, FLAC, M4A)",
    val terminalExpanded: Boolean = true,
    val logs: List<String> = listOf("[SYS] StemStudio AI готов. Движок: Chaquopy Python 3.11 + ONNX Runtime ARM64"),
    val stems: List<StemTrackUi> = defaultStems(),
    val stemsReady: Boolean = false,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isExporting: Boolean = false,
    val toastMessage: String? = null
)

private fun defaultStems() = listOf(
    StemTrackUi("vocals", "Вокал", NeonMagenta),
    StemTrackUi("drums", "Ударные / Бит", NeonCyan),
    StemTrackUi("bass", "Бас", NeonLime),
    StemTrackUi("guitar", "Гитара", NeonAmber),
    StemTrackUi("piano", "Пианино", NeonViolet),
    StemTrackUi("other", "Другое", NeonCoral)
)

class ProgressBridge(private val onReport: (Int, String) -> Unit) {
    fun report(percent: Int, message: String) {
        onReport(percent, message)
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    private val player = MultiTrackPlayer()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    init {
        player.setPositionListener { currentMs, totalMs, playing ->
            _uiState.update {
                it.copy(
                    currentPositionMs = currentMs,
                    durationMs = totalMs,
                    isPlaying = playing
                )
            }
        }
    }

    fun onTrackSelected(uri: Uri) {
        val name = queryDisplayName(uri) ?: "track_audio"
        player.stop()
        _uiState.update {
            it.copy(
                selectedUri = uri,
                selectedFileName = name,
                stemsReady = false,
                progressPercent = 0,
                statusText = "Готов к разделению: $name"
            )
        }
        appendLog("[UI] Выбран трек: $name")
    }

    fun toggleTerminal() {
        _uiState.update { it.copy(terminalExpanded = !it.terminalExpanded) }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun startSeparation() {
        val uri = _uiState.value.selectedUri ?: return
        if (_uiState.value.isProcessing) return

        player.stop()
        _uiState.update {
            it.copy(
                isProcessing = true,
                stemsReady = false,
                progressPercent = 1,
                statusText = "Декодирование аудио в 44.1 kHz PCM WAV...",
                terminalExpanded = true
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val workDir = File(ctx.filesDir, "session_stems").apply {
                deleteRecursively()
                mkdirs()
            }
            val modelDir = File(ctx.filesDir, "models").apply { mkdirs() }
            val decodedWav = File(ctx.cacheDir, "decoded_input.wav")

            try {
                appendLog("[STAGE 1/2] Нативное декодирование MediaCodec -> 16-bit PCM WAV...")
                AudioDecoder.decodeToWav44100Stereo(
                    context = ctx,
                    sourceUri = uri,
                    outputWavFile = decodedWav,
                    onLog = { msg -> appendLog(msg) }
                )

                _uiState.update {
                    it.copy(progressPercent = 5, statusText = "Запуск нейросети htdemucs_6s...")
                }

                val py = Python.getInstance()
                val separatorModule = py.getModule("separator")
                val onnxBridge = OnnxInferenceBridge()
                val progressBridge = ProgressBridge { pct, msg ->
                    appendLog(msg)
                    _uiState.update { st ->
                        st.copy(
                            progressPercent = pct,
                            statusText = msg.substringAfter("] ", msg)
                        )
                    }
                }

                appendLog("[STAGE 2/2] Вызов separator.separate_track()...")
                val pyResult = separatorModule.callAttr(
                    "separate_track",
                    decodedWav.absolutePath,
                    workDir.absolutePath,
                    modelDir.absolutePath,
                    onnxBridge,
                    progressBridge,
                    true,
                    0.25
                )

                val stemFiles = LinkedHashMap<String, File>()
                val pyMap = pyResult.asMap()
                for ((k, v) in pyMap) {
                    stemFiles[k.toString()] = File(v.toString())
                }

                player.loadStems(stemFiles)
                val updatedStems = defaultStems().map { stem ->
                    stem.copy(file = stemFiles[stem.id])
                }

                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        progressPercent = 100,
                        statusText = "Готово! 6 стемов синхронизированы",
                        stems = updatedStems,
                        stemsReady = true,
                        durationMs = player.getDurationMs(),
                        currentPositionMs = 0L
                    )
                }
            } catch (e: Exception) {
                appendLog("[FATAL] ${e.javaClass.simpleName}: ${e.message}")
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        statusText = "Ошибка: ${e.message}",
                        toastMessage = "Ошибка обработки: ${e.message}"
                    )
                }
            } finally {
                if (decodedWav.exists()) decodedWav.delete()
            }
        }
    }

    fun togglePlayPause() {
        if (!_uiState.value.stemsReady) return
        if (_uiState.value.isPlaying) player.pause() else player.play()
    }

    fun seekTo(fraction: Float) {
        if (!_uiState.value.stemsReady) return
        player.seekToFraction(fraction)
    }

    fun onStemVolumeChange(stemId: String, volume: Float) {
        updateStemUi(stemId) { it.copy(volume = volume) }
    }

    fun onStemMuteToggle(stemId: String) {
        updateStemUi(stemId) { it.copy(isMuted = !it.isMuted) }
    }

    fun onStemSoloToggle(stemId: String) {
        updateStemUi(stemId) { it.copy(isSolo = !it.isSolo) }
    }

    private fun updateStemUi(stemId: String, transform: (StemTrackUi) -> StemTrackUi) {
        _uiState.update { state ->
            val newList = state.stems.map { stem ->
                if (stem.id == stemId) {
                    val updated = transform(stem)
                    player.updateTrack(updated.id, updated.volume, updated.isMuted, updated.isSolo)
                    updated
                } else stem
            }
            state.copy(stems = newList)
        }
    }

    fun exportSingleStem(stem: StemTrackUi) {
        if (_uiState.value.isExporting) return
        val baseName = (_uiState.value.selectedFileName ?: "track").substringBeforeLast('.')
        val outName = "${baseName}_${stem.id}.wav"

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isExporting = true) }
            try {
                val saved = player.exportSingleStemToMediaStore(getApplication(), stem.id, outName)
                appendLog("[SAVE] Стем сохранён: $saved")
                _uiState.update { it.copy(isExporting = false, toastMessage = "Сохранено: $saved") }
            } catch (e: Exception) {
                appendLog("[ERROR] Ошибка сохранения: ${e.message}")
                _uiState.update { it.copy(isExporting = false, toastMessage = "Ошибка: ${e.message}") }
            }
        }
    }

    fun exportCustomMix() {
        if (_uiState.value.isExporting || !_uiState.value.stemsReady) return
        val baseName = (_uiState.value.selectedFileName ?: "track").substringBeforeLast('.')
        val outName = "${baseName}_custom_mix_${System.currentTimeMillis() / 1000}.wav"

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isExporting = true) }
            appendLog("[MIX] Рендер кастомного микса -> Music/StemStudio/$outName...")
            try {
                val saved = player.exportCurrentMixToMediaStore(getApplication(), outName) { pct ->
                    if (pct % 25 == 0) appendLog("[MIX] Рендер микса: $pct%")
                }
                appendLog("[MIX] Готово! Файл сохранён: $saved")
                _uiState.update { it.copy(isExporting = false, toastMessage = "Микс сохранён: $saved") }
            } catch (e: Exception) {
                appendLog("[ERROR] Ошибка рендера микса: ${e.message}")
                _uiState.update { it.copy(isExporting = false, toastMessage = "Ошибка: ${e.message}") }
            }
        }
    }

    private fun appendLog(line: String) {
        val ts = timeFmt.format(Date())
        val formatted = "[$ts] $line"
        _uiState.update { st ->
            st.copy(logs = (st.logs + formatted).takeLast(300))
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cursor = getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
    }
}
