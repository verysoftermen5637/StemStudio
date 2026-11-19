package com.stemstudio.app.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer

class OnnxInferenceBridge : Closeable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var inputName: String = "mix"

    fun loadModel(modelPath: String) {
        close()
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(cores)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setMemoryPatternOptimization(true)
        }
        val ortSession = env.createSession(modelPath, options)
        session = ortSession
        inputName = ortSession.inputNames.firstOrNull() ?: "mix"
    }

    fun runChunk(flatMix: FloatArray): FloatArray {
        val activeSession = session ?: error("ONNX session is not loaded")
        val segmentSamples = flatMix.size / 2L
        val shape = longArrayOf(1L, 2L, segmentSamples)

        OnnxTensor.createTensor(env, FloatBuffer.wrap(flatMix), shape).use { inputTensor ->
            activeSession.run(mapOf(inputName to inputTensor)).use { results ->
                val outTensor = results[0] as OnnxTensor
                val floatBuf = outTensor.floatBuffer
                val outArray = FloatArray(floatBuf.remaining())
                floatBuf.get(outArray)
                return outArray
            }
        }
    }

    override fun close() {
        try {
            session?.close()
        } catch (_: Exception) {
        } finally {
            session = null
        }
    }
}
