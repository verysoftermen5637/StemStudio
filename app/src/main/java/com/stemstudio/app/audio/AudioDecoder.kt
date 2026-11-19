package com.stemstudio.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioDecoder {

    private const val TARGET_SAMPLE_RATE = 44100
    private const val TARGET_CHANNELS = 2
    private const val TIMEOUT_US = 10_000L

    fun decodeToWav44100Stereo(
        context: Context,
        sourceUri: Uri,
        outputWavFile: File,
        onLog: (String) -> Unit
    ): File {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(context, sourceUri, null)

            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = fmt
                    break
                }
            }

            if (audioTrackIndex < 0 || inputFormat == null) {
                throw IllegalArgumentException("В выбранном файле не найдена аудиодорожка")
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            var srcSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var srcChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            onLog("[DECODER] Кодек: $mime | Исходник: ${srcSampleRate}Hz, ${srcChannels}ch")

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            outputWavFile.parentFile?.mkdirs()
            val outStream = BufferedOutputStream(FileOutputStream(outputWavFile), 65536)
            outStream.write(ByteArray(44))

            var totalPcmBytesWritten = 0L
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            var resamplePos = 0.0
            var prevLeft = 0f
            var prevRight = 0f
            var hasPrevSample = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuffer = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inIndex, 0, sampleSize, extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFmt = codec.outputFormat
                        if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            srcSampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            srcChannels = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        onLog("[DECODER] Формат PCM обновлён: ${srcSampleRate}Hz, ${srcChannels}ch")
                    }

                    outIndex >= 0 -> {
                        val outBuffer = codec.getOutputBuffer(outIndex)
                        if (outBuffer != null && bufferInfo.size > 0) {
                            outBuffer.position(bufferInfo.offset)
                            outBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            val rawBytes = ByteArray(bufferInfo.size)
                            outBuffer.get(rawBytes)

                            val shortBuf = ByteBuffer.wrap(rawBytes)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .asShortBuffer()
                            val totalShorts = shortBuf.remaining()
                            val frameCount = totalShorts / srcChannels

                            if (frameCount > 0) {
                                val stereo = ShortArray(frameCount * 2)
                                if (srcChannels == 1) {
                                    for (f in 0 until frameCount) {
                                        val s = shortBuf.get(f)
                                        stereo[f * 2] = s
                                        stereo[f * 2 + 1] = s
                                    }
                                } else {
                                    for (f in 0 until frameCount) {
                                        stereo[f * 2] = shortBuf.get(f * srcChannels)
                                        stereo[f * 2 + 1] = shortBuf.get(f * srcChannels + 1)
                                    }
                                }

                                val outBytes = if (srcSampleRate == TARGET_SAMPLE_RATE) {
                                    shortArrayToLeBytes(stereo)
                                } else {
                                    val ratio = srcSampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
                                    val resampled = ArrayList<Short>((frameCount / ratio).toInt() * 2 + 8)
                                    while (resamplePos < frameCount) {
                                        val idx = resamplePos.toInt()
                                        val frac = (resamplePos - idx).toFloat()

                                        val l0: Float
                                        val r0: Float
                                        val l1: Float
                                        val r1: Float

                                        if (idx == 0 && hasPrevSample) {
                                            l0 = prevLeft
                                            r0 = prevRight
                                            l1 = stereo[0].toFloat()
                                            r1 = stereo[1].toFloat()
                                        } else {
                                            val nextIdx = (idx + 1).coerceAtMost(frameCount - 1)
                                            l0 = stereo[idx * 2].toFloat()
                                            r0 = stereo[idx * 2 + 1].toFloat()
                                            l1 = stereo[nextIdx * 2].toFloat()
                                            r1 = stereo[nextIdx * 2 + 1].toFloat()
                                        }

                                        val outL = (l0 + (l1 - l0) * frac).toInt().coerceIn(-32768, 32767).toShort()
                                        val outR = (r0 + (r1 - r0) * frac).toInt().coerceIn(-32768, 32767).toShort()
                                        resampled.add(outL)
                                        resampled.add(outR)
                                        resamplePos += ratio
                                    }
                                    resamplePos -= frameCount
                                    prevLeft = stereo[(frameCount - 1) * 2].toFloat()
                                    prevRight = stereo[(frameCount - 1) * 2 + 1].toFloat()
                                    hasPrevSample = true

                                    val arr = ShortArray(resampled.size) { resampled[it] }
                                    shortArrayToLeBytes(arr)
                                }

                                outStream.write(outBytes)
                                totalPcmBytesWritten += outBytes.size
                            }
                        }

                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            outStream.flush()
            outStream.close()

            writeWavHeader(outputWavFile, totalPcmBytesWritten, TARGET_SAMPLE_RATE, TARGET_CHANNELS, 16)
            val durationSec = totalPcmBytesWritten.toDouble() / (TARGET_SAMPLE_RATE * TARGET_CHANNELS * 2)
            onLog("[DECODER] WAV готов: %.1f сек (%.1f MB)".format(durationSec, outputWavFile.length() / 1_048_576.0))
            return outputWavFile
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {
            }
            extractor.release()
        }
    }

    private fun shortArrayToLeBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shorts)
        return bytes
    }

    fun writeWavHeader(
        file: File,
        pcmDataSize: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((36 + pcmDataSize).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign)
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmDataSize.toInt())
        }
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header.array())
        }
    }
}
