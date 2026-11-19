package com.stemstudio.app.audio

import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.tanh

class MultiTrackPlayer {

    companion object {
        const val SAMPLE_RATE = 44100
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = 4
        private const val CHUNK_FRAMES = 2048
    }

    data class TrackParams(
        @Volatile var volume: Float = 1.0f,
        @Volatile var muted: Boolean = false,
        @Volatile var solo: Boolean = false
    )

    private data class WavSource(
        val id: String,
        val file: File,
        val dataOffset: Long,
        val totalFrames: Long
    )

    private val sources = mutableListOf<WavSource>()
    private val trackParams = LinkedHashMap<String, TrackParams>()

    private val isPlayingFlag = AtomicBoolean(false)
    private val currentFrame = AtomicLong(0L)
    private var totalFrames: Long = 0L

    private var playbackThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private var positionListener: ((currentMs: Long, totalMs: Long, playing: Boolean) -> Unit)? = null

    fun setPositionListener(listener: (Long, Long, Boolean) -> Unit) {
        positionListener = listener
    }

    fun loadStems(stemFiles: Map<String, File>) {
        stop()
        sources.clear()
        trackParams.clear()

        var maxFrames = 0L
        for ((id, file) in stemFiles) {
            if (!file.exists()) continue
            val (offset, dataSize) = parseWavDataChunk(file)
            val frames = dataSize / BYTES_PER_FRAME
            sources.add(WavSource(id, file, offset, frames))
            trackParams[id] = TrackParams()
            if (frames > maxFrames) maxFrames = frames
        }

        totalFrames = maxFrames
        currentFrame.set(0L)
        notifyPosition()
    }

    fun updateTrack(id: String, volume: Float, muted: Boolean, solo: Boolean) {
        trackParams[id]?.let {
            it.volume = volume.coerceIn(0f, 1.5f)
            it.muted = muted
            it.solo = solo
        }
    }

    fun getDurationMs(): Long = (totalFrames * 1000L) / SAMPLE_RATE

    fun play() {
        if (sources.isEmpty() || isPlayingFlag.get()) return
        if (currentFrame.get() >= totalFrames) {
            currentFrame.set(0L)
        }
        isPlayingFlag.set(true)
        startPlaybackLoop()
        notifyPosition()
    }

    fun pause() {
        isPlayingFlag.set(false)
        playbackThread?.join(300)
        playbackThread = null
        releaseAudioTrack()
        notifyPosition()
    }

    fun stop() {
        pause()
        currentFrame.set(0L)
        notifyPosition()
    }

    fun seekToFraction(fraction: Float) {
        val target = (fraction.coerceIn(0f, 1f) * totalFrames).toLong()
        currentFrame.set(target)
        notifyPosition()
    }

    fun release() {
        stop()
        sources.clear()
        trackParams.clear()
    }

    private fun startPlaybackLoop() {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(CHUNK_FRAMES * BYTES_PER_FRAME * 2)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        track.play()

        playbackThread = Thread {
            val rafs = sources.map { RandomAccessFile(it.file, "r") }
            val readBuf = ByteArray(CHUNK_FRAMES * BYTES_PER_FRAME)
            val mixFloat = FloatArray(CHUNK_FRAMES * CHANNELS)
            val outBytes = ByteArray(CHUNK_FRAMES * BYTES_PER_FRAME)
            val outShortBuf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

            try {
                while (isPlayingFlag.get()) {
                    val framePos = currentFrame.get()
                    if (framePos >= totalFrames) {
                        isPlayingFlag.set(false)
                        currentFrame.set(0L)
                        break
                    }

                    val framesToRead = minOf(CHUNK_FRAMES.toLong(), totalFrames - framePos).toInt()
                    val sampleCount = framesToRead * CHANNELS
                    mixFloat.fill(0f, 0, sampleCount)

                    val anySolo = trackParams.values.any { it.solo }

                    for (i in sources.indices) {
                        val src = sources[i]
                        val params = trackParams[src.id] ?: continue
                        val gain = effectiveGain(params, anySolo)
                        if (gain <= 0.0001f || framePos >= src.totalFrames) continue

                        val availFrames = minOf(framesToRead.toLong(), src.totalFrames - framePos).toInt()
                        val bytesToRead = availFrames * BYTES_PER_FRAME

                        val raf = rafs[i]
                        raf.seek(src.dataOffset + framePos * BYTES_PER_FRAME)
                        val read = raf.read(readBuf, 0, bytesToRead)
                        if (read <= 0) continue

                        val shortsRead = read / 2
                        val sb = ByteBuffer.wrap(readBuf, 0, read)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()
                        for (s in 0 until shortsRead) {
                            mixFloat[s] += (sb.get(s) / 32768f) * gain
                        }
                    }

                    outShortBuf.clear()
                    for (s in 0 until sampleCount) {
                        val sample = mixFloat[s]
                        val limited = if (sample > 0.95f || sample < -0.95f) tanh(sample.toDouble()).toFloat() else sample
                        outShortBuf.put((limited * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                    }

                    track.write(outBytes, 0, framesToRead * BYTES_PER_FRAME)
                    currentFrame.addAndGet(framesToRead.toLong())
                    notifyPosition()
                }
            } finally {
                rafs.forEach { try { it.close() } catch (_: Exception) {} }
                releaseAudioTrack()
                notifyPosition()
            }
        }.apply {
            name = "StemStudio-MixerThread"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun exportCurrentMixToMediaStore(
        context: Context,
        outputFileName: String,
        onProgress: (Int) -> Unit
    ): String {
        val tempFile = File(context.cacheDir, "export_mix_tmp.wav")
        renderMixToFile(tempFile, singleStemId = null, onProgress = onProgress)
        val savedPath = saveWavToMusicFolder(context, tempFile, outputFileName)
        tempFile.delete()
        return savedPath
    }

    fun exportSingleStemToMediaStore(
        context: Context,
        stemId: String,
        outputFileName: String
    ): String {
        val src = sources.firstOrNull { it.id == stemId }
            ?: error("Стем $stemId не найден")
        return saveWavToMusicFolder(context, src.file, outputFileName)
    }

    private fun renderMixToFile(
        outFile: File,
        singleStemId: String?,
        onProgress: (Int) -> Unit
    ) {
        if (sources.isEmpty() || totalFrames == 0L) error("Нет загруженных стемов для экспорта")

        val rafs = sources.map { RandomAccessFile(it.file, "r") }
        val readBuf = ByteArray(CHUNK_FRAMES * BYTES_PER_FRAME)
        val mixFloat = FloatArray(CHUNK_FRAMES * CHANNELS)
        val outBytes = ByteArray(CHUNK_FRAMES * BYTES_PER_FRAME)
        val outShortBuf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val anySolo = trackParams.values.any { it.solo }

        try {
            BufferedOutputStream(FileOutputStream(outFile), 65536).use { out ->
                out.write(ByteArray(44))
                var framePos = 0L
                while (framePos < totalFrames) {
                    val framesToRead = minOf(CHUNK_FRAMES.toLong(), totalFrames - framePos).toInt()
                    val sampleCount = framesToRead * CHANNELS
                    mixFloat.fill(0f, 0, sampleCount)

                    for (i in sources.indices) {
                        val src = sources[i]
                        if (singleStemId != null && src.id != singleStemId) continue
                        val params = trackParams[src.id] ?: continue
                        val gain = if (singleStemId != null) 1f else effectiveGain(params, anySolo)
                        if (gain <= 0.0001f || framePos >= src.totalFrames) continue

                        val availFrames = minOf(framesToRead.toLong(), src.totalFrames - framePos).toInt()
                        val bytesToRead = availFrames * BYTES_PER_FRAME
                        val raf = rafs[i]
                        raf.seek(src.dataOffset + framePos * BYTES_PER_FRAME)
                        val read = raf.read(readBuf, 0, bytesToRead)
                        if (read <= 0) continue

                        val sb = ByteBuffer.wrap(readBuf, 0, read)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()
                        for (s in 0 until read / 2) {
                            mixFloat[s] += (sb.get(s) / 32768f) * gain
                        }
                    }

                    outShortBuf.clear()
                    for (s in 0 until sampleCount) {
                        val v = mixFloat[s]
                        val limited = if (v > 0.95f || v < -0.95f) tanh(v.toDouble()).toFloat() else v
                        outShortBuf.put((limited * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                    }

                    out.write(outBytes, 0, framesToRead * BYTES_PER_FRAME)
                    framePos += framesToRead
                    onProgress(((framePos * 100) / totalFrames).toInt())
                }
            }
            AudioDecoder.writeWavHeader(outFile, totalFrames * BYTES_PER_FRAME, SAMPLE_RATE, CHANNELS, 16)
        } finally {
            rafs.forEach { try { it.close() } catch (_: Exception) {} }
        }
    }

    private fun saveWavToMusicFolder(context: Context, sourceWav: File, displayName: String): String {
        val safeName = if (displayName.endsWith(".wav", ignoreCase = true)) displayName else "$displayName.wav"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/StemStudio")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Не удалось создать запись в MediaStore")

            resolver.openOutputStream(uri)?.use { out ->
                sourceWav.inputStream().use { inp -> inp.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "Music/StemStudio/$safeName"
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "StemStudio"
            )
            dir.mkdirs()
            val dest = File(dir, safeName)
            sourceWav.copyTo(dest, overwrite = true)
            return dest.absolutePath
        }
    }

    private fun effectiveGain(params: TrackParams, anySolo: Boolean): Float {
        return when {
            anySolo -> if (params.solo && !params.muted) params.volume else 0f
            params.muted -> 0f
            else -> params.volume
        }
    }

    private fun parseWavDataChunk(file: File): Pair<Long, Long> {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 44) return 44L to 0L
            raf.seek(12)
            val hdr = ByteArray(8)
            while (raf.filePointer + 8 <= raf.length()) {
                raf.readFully(hdr)
                val chunkId = String(hdr, 0, 4, Charsets.US_ASCII)
                val chunkSize = ByteBuffer.wrap(hdr, 4, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .int.toLong() and 0xFFFFFFFFL
                if (chunkId == "data") {
                    return raf.filePointer to chunkSize
                }
                raf.seek(raf.filePointer + chunkSize)
            }
            return 44L to (raf.length() - 44L).coerceAtLeast(0L)
        }
    }

    private fun releaseAudioTrack() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (_: Exception) {
        } finally {
            audioTrack = null
        }
    }

    private fun notifyPosition() {
        val curMs = (currentFrame.get() * 1000L) / SAMPLE_RATE
        positionListener?.invoke(curMs, getDurationMs(), isPlayingFlag.get())
    }
}
