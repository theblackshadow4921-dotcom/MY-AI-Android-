package com.myai.assistant

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class OfflineHindiAsr(private val context: Context) {

    companion object {
        private const val BASE =
            "https://huggingface.co/ippocode/indic-asr-onnx/resolve/main/models/whisper-small-hi/"
    }

    private val modelDir =
        File(context.filesDir, "whisper-small-hi")

    private val files = listOf(
        "encoder.int8.onnx" to (90L * 1024 * 1024),
        "decoder.int8.onnx" to (200L * 1024 * 1024),
        "tokens.txt" to (100L * 1024)
    )

    fun ensureModel(): Boolean {
        if (!modelDir.exists() && !modelDir.mkdirs()) return false

        for ((name, minimumSize) in files) {
            val target = File(modelDir, name)
            if (target.exists() && target.length() >= minimumSize) continue

            val temp = File(modelDir, "$name.part")
            temp.delete()

            var connection: HttpURLConnection? = null
            try {
                connection = (URL(BASE + name).openConnection()
                    as HttpURLConnection).apply {
                    connectTimeout = 30000
                    readTimeout = 300000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "SHAKTI-AI-Android")
                }

                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException(
                        "Model download HTTP ${connection.responseCode}"
                    )
                }

                connection.inputStream.use { input ->
                    FileOutputStream(temp).use { output ->
                        input.copyTo(output, 1024 * 1024)
                        output.fd.sync()
                    }
                }

                if (temp.length() < minimumSize) {
                    throw IllegalStateException("Incomplete model: $name")
                }

                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    throw IllegalStateException("Cannot save model: $name")
                }
            } catch (e: Exception) {
                temp.delete()
                throw e
            } finally {
                connection?.disconnect()
            }
        }
        return files.all { (name, size) ->
            File(modelDir, name).let { it.exists() && it.length() >= size }
        }
    }

    fun transcribe(samples: FloatArray): String {
        require(samples.size >= 4000) { "Audio बहुत छोटा है" }

        val config = OfflineRecognizerConfig(
            featConfig = getFeatureConfig(
                sampleRate = 16000,
                featureDim = 80
            ),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(modelDir, "encoder.int8.onnx").absolutePath,
                    decoder = File(modelDir, "decoder.int8.onnx").absolutePath,
                    language = "hi",
                    task = "transcribe"
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = 2,
                modelType = "whisper"
            )
        )

        val recognizer = OfflineRecognizer(config = config)
        val stream = recognizer.createStream()

        try {
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
            recognizer.release()
        }
    }
}
