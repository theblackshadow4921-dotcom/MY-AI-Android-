package com.myai.assistant

import android.Manifest
import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.k2fsa.sherpa.onnx.getKwsModelConfig
import com.k2fsa.sherpa.onnx.getKeywordsFile
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class WakeWordService : Service() {

    companion object {
        const val ACTION_WAKE_WORD_DETECTED =
            "com.myai.assistant.WAKE_WORD_DETECTED"
        const val ACTION_STATUS =
            "com.myai.assistant.SHAKTI_STATUS"
        const val ACTION_TRANSCRIPT =
            "com.myai.assistant.SHAKTI_TRANSCRIPT"
        const val ACTION_REPLY =
            "com.myai.assistant.SHAKTI_REPLY"
        const val ACTION_STOP =
            "com.myai.assistant.STOP_WAKE_WORD"

        private const val TAG = "MYAI_WAKE"
        private const val CHANNEL_ID = "myai_wake_channel"
        private const val NOTIFICATION_ID = 1001
        private const val SAMPLE_RATE = 16000
    }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var serviceActive = false
    @Volatile private var detectorRunning = false
    @Volatile private var audioRecord: AudioRecord? = null

    private var worker: Thread? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("hi", "IN"))
                ttsReady =
                    result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }

        tts?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}

                override fun onDone(id: String?) {
                    handler.post {
                        when (id) {
                            "SHAKTI_LISTEN" -> startRecognitionNow()
                            "SHAKTI_RESTART" -> startDetector()
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    handleTtsError(id)
                }

                override fun onError(id: String?, errorCode: Int) {
                    handleTtsError(id)
                }
            }
        )
    }

    private fun handleTtsError(id: String?) {
        handler.post {
            when (id) {
                "SHAKTI_LISTEN" -> startRecognitionNow()
                "SHAKTI_RESTART" -> startDetector()
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (intent?.action == ACTION_STOP) {
            serviceActive = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Microphone permission missing")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val notification = buildNotification()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            serviceActive = true
            startDetector()
        } catch (e: Exception) {
            Log.e(TAG, "Foreground service start failed", e)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startDetector() {
        if (!serviceActive || detectorRunning) return

        destroyRecognizer()
        detectorRunning = true
        worker = Thread { runDetector() }.also { it.start() }
    }

    private fun runDetector() {
        var spotter: KeywordSpotter? = null
        var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null
        var recorder: AudioRecord? = null
        var wakeDetected = false
        var failed = false

        try {
            val config = KeywordSpotterConfig(
                featConfig = getFeatureConfig(
                    sampleRate = SAMPLE_RATE,
                    featureDim = 80
                ),
                modelConfig = getKwsModelConfig(type = 1)!!,
                keywordsFile = getKeywordsFile(type = 1)
            )

            spotter = KeywordSpotter(
                assetManager = assets,
                config = config
            )

            stream = spotter.createStream()

            if (stream.ptr == 0L) {
                throw IllegalStateException("KWS stream नहीं बना")
            }

            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT

            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                channelConfig,
                audioFormat
            )

            if (minBuffer <= 0) {
                throw IllegalStateException("Audio buffer गलत है")
            }

            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                channelConfig,
                audioFormat,
                maxOf(minBuffer, 3200) * 2
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("Microphone शुरू नहीं हुआ")
            }

            audioRecord = recorder
            recorder.startRecording()

            Log.i(TAG, "Listening for SHAKTI")
            sendStatus("SHAKTI चालू है। सिर्फ SHAKTI बोलें।")

            val buffer = ShortArray(1600)

            while (serviceActive && !wakeDetected) {
                val count = recorder.read(
                    buffer,
                    0,
                    buffer.size
                )

                if (count > 0) {
                    val samples = FloatArray(count) {
                        buffer[it] / 32768.0f
                    }

                    stream.acceptWaveform(
                        samples,
                        sampleRate = SAMPLE_RATE
                    )

                    while (serviceActive && spotter.isReady(stream)) {
                        spotter.decode(stream)
                        val result = spotter.getResult(stream)

                        if (result.keyword.isNotBlank()) {
                            Log.i(
                                TAG,
                                "Detected: ${result.keyword}"
                            )
                            wakeDetected = true
                            spotter.reset(stream)
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            failed = true
            Log.e(TAG, "Wake detector error", e)
            sendStatus("सर, माइक्रोफोन में समस्या आई है।")
        } finally {
            try {
                recorder?.let {
                    if (it.recordingState ==
                        AudioRecord.RECORDSTATE_RECORDING
                    ) {
                        it.stop()
                    }
                    it.release()
                }
            } catch (_: Exception) {}

            audioRecord = null

            try {
                stream?.release()
                spotter?.release()
            } catch (_: Exception) {}

            detectorRunning = false
            Log.i(TAG, "Wake detector stopped")

            if (serviceActive && wakeDetected) {
                handler.postDelayed({
                    if (serviceActive) beginSpeechRecognition()
                }, 500)
            } else if (serviceActive && failed) {
                handler.postDelayed({
                    startDetector()
                }, 3000)
            }
        }
    }

    private fun beginSpeechRecognition() {
        if (!serviceActive) return
        sendStatus("जी सर, मैं सुन रहा हूँ। अब बोलिए।")
        startRecognitionNow()
    }

    private fun startRecognitionNow() {
        if (!serviceActive) return
        worker = Thread {
            runOfflineHindiRecognition()
        }.also { it.start() }
    }

    private fun runOfflineHindiRecognition() {
        var recorder: AudioRecord? = null

        try {
            sendStatus("हिंदी Offline Voice मॉडल तैयार कर रहा हूँ।")

            val asr = OfflineHindiAsr(this)
            if (!asr.ensureModel()) {
                throw IllegalStateException("Hindi model डाउनलोड नहीं हुआ")
            }

            if (!serviceActive) return
            sendStatus("सर, अब बोलिए। मैं आपकी आवाज़ सुन रहा हूँ।")

            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) {
                throw IllegalStateException("Audio buffer गलत है")
            }

            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, 3200) * 2
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("Microphone शुरू नहीं हुआ")
            }

            audioRecord = recorder
            recorder.startRecording()

            val buffer = ShortArray(1600)
            val audio = ArrayList<Float>()
            var speechStarted = false
            var silentSamples = 0
            val maxSamples = SAMPLE_RATE * 12
            val silenceLimit = SAMPLE_RATE * 15 / 10

            while (serviceActive && audio.size < maxSamples) {
                val count = recorder.read(buffer, 0, buffer.size)
                if (count <= 0) continue

                var energy = 0.0
                for (i in 0 until count) {
                    val value = buffer[i].toFloat()
                    energy += value * value
                    audio.add(value / 32768.0f)
                }

                val rms = kotlin.math.sqrt(energy / count)
                if (rms > 450.0) {
                    speechStarted = true
                    silentSamples = 0
                } else if (speechStarted) {
                    silentSamples += count
                }

                if (speechStarted && silentSamples >= silenceLimit) {
                    break
                }
            }

            try {
                if (recorder.recordingState ==
                    AudioRecord.RECORDSTATE_RECORDING
                ) recorder.stop()
            } catch (_: Exception) {}

            recorder.release()
            recorder = null
            audioRecord = null

            if (!serviceActive) return

            if (!speechStarted || audio.size < 4000) {
                handler.post {
                    if (serviceActive) {
                        speakAndRestart("सर, आपकी आवाज़ साफ़ नहीं आई। फिर से बोलिए।")
                    }
                }
                return
            }

            sendStatus("सर, आपकी हिंदी आवाज़ पहचान रहा हूँ।")
            val spoken = asr.transcribe(audio.toFloatArray())

            handler.post {
                if (!serviceActive) return@post

                if (spoken.isBlank()) {
                    speakAndRestart("सर, मैं आपकी बात समझ नहीं पाया। फिर से बोलिए।")
                } else {
                    Log.i(TAG, "Offline Hindi transcript: $spoken")
                    sendBroadcast(
                        Intent(ACTION_TRANSCRIPT)
                            .setPackage(packageName)
                            .putExtra("message", spoken)
                    )
                    askGemini(spoken)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Offline Hindi recognition failed", e)
            handler.post {
                if (serviceActive) {
                    sendStatus("Offline Voice में समस्या: ${e.message}")
                    speakAndRestart(
                        "सर, हिंदी Offline Voice तैयार नहीं हो पाया। इंटरनेट और मॉडल डाउनलोड जाँचें।"
                    )
                }
            }
        } finally {
            try {
                recorder?.let {
                    if (it.recordingState ==
                        AudioRecord.RECORDSTATE_RECORDING
                    ) it.stop()
                    it.release()
                }
            } catch (_: Exception) {}
            audioRecord = null
        }
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}

        speechRecognizer = null
    }


    private fun handleAppCommand(question: String): Boolean {
        val text = question.lowercase(Locale.ROOT)

        val openWords = listOf(
            "खोलो", "खोल दो", "खोलिए", "open",
            "launch", "चलाओ", "चला दो"
        )

        if (openWords.none { text.contains(it) }) return false

        val aliases = listOf(
            listOf("youtube", "यूट्यूब") to "com.google.android.youtube",
            listOf("whatsapp", "व्हाट्सएप") to "com.whatsapp",
            listOf("instagram", "इंस्टाग्राम") to "com.instagram.android",
            listOf("chrome", "क्रोम") to "com.android.chrome",
            listOf("chatgpt", "chat gpt", "चैट जीपीटी") to "com.openai.chatgpt",
            listOf("telegram", "टेलीग्राम") to "org.telegram.messenger",
            listOf("spotify", "स्पॉटिफाई") to "com.spotify.music",
            listOf("play store", "प्ले स्टोर") to "com.android.vending",
            listOf("gmail", "जीमेल") to "com.google.android.gm"
        )

        val knownPackage = aliases.firstOrNull { item ->
            item.first.any { text.contains(it) }
        }?.second

        val launcherQuery = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val apps = packageManager.queryIntentActivities(launcherQuery, 0)

        val target = text.replace(
            Regex(
                "hi shakti|शक्ति|सर|खोल दो|खोलो|खोलिए|open|launch|चलाओ|चला दो|please",
                RegexOption.IGNORE_CASE
            ),
            ""
        ).trim()

        val matched = if (knownPackage == null) {
            apps.firstOrNull { info ->
                val label = info.loadLabel(packageManager)
                    .toString().lowercase(Locale.ROOT)
                target.isNotBlank() &&
                    (label.contains(target) || target.contains(label))
            }
        } else {
            apps.firstOrNull {
                it.activityInfo.packageName == knownPackage
            }
        }

        val packageToOpen =
            knownPackage ?: matched?.activityInfo?.packageName

        if (packageToOpen.isNullOrBlank()) {
            speakAndRestart("सर, यह ऐप फोन में नहीं मिला।")
            return true
        }

        val launchIntent =
            packageManager.getLaunchIntentForPackage(packageToOpen)

        if (launchIntent == null) {
            speakAndRestart("सर, यह ऐप इंस्टॉल नहीं है।")
            return true
        }

        val appName = matched?.loadLabel(packageManager)?.toString()
            ?: target.ifBlank { "ऐप" }

        val channelId = "myai_app_launch"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "SHAKTI AI App Launch",
                NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            packageToOpen.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("SHAKTI AI — ऐप खोलें")
            .setContentText("$appName खोलने के लिए यहाँ दबाएँ")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(2002, notification)

        speakAndRestart(
            "जी सर, $appName खोलने के लिए नोटिफिकेशन दबाइए।"
        )

        return true
    }

    private fun askGemini(question: String) {
        if (handleAppCommand(question)) return

        sendStatus("सर, जवाब तैयार कर रहा हूँ।")

        thread {
            var connection: HttpURLConnection? = null

            val reply = try {
                connection = URL(
                    "https://my-ai-gemini.onrender.com/chat"
                ).openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 15000
                connection.readTimeout = 120000

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                val body = JSONObject()
                    .put("message", question)
                    .toString()

                connection.outputStream.use {
                    it.write(
                        body.toByteArray(Charsets.UTF_8)
                    )
                }

                val responseCode = connection.responseCode

                val responseStream =
                    if (responseCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                val response = responseStream
                    .bufferedReader()
                    .use { it.readText() }

                JSONObject(response).optString(
                    "reply",
                    "सर, जवाब नहीं मिल पाया।"
                )

            } catch (e: Exception) {
                Log.e(TAG, "Gemini request failed", e)
                "सर, इंटरनेट कनेक्शन में समस्या है।"
            } finally {
                connection?.disconnect()
            }

            handler.post {
                sendBroadcast(
                    Intent(ACTION_REPLY)
                        .setPackage(packageName)
                        .putExtra("message", reply)
                )

                speakAndRestart(reply)
            }
        }
    }

    private fun speakAndRestart(message: String) {
        speak(message, "SHAKTI_RESTART")
    }

    private fun speak(
        message: String,
        utteranceId: String
    ) {
        if (!serviceActive) return

        if (ttsReady && tts != null) {
            val result = tts?.speak(
                message,
                TextToSpeech.QUEUE_FLUSH,
                null,
                utteranceId
            )

            if (result == TextToSpeech.ERROR) {
                handler.postDelayed({
                    startDetector()
                }, 1500)
            }
        } else {
            handler.postDelayed({
                if (serviceActive) {
                    if (utteranceId == "SHAKTI_LISTEN") {
                        startRecognitionNow()
                    } else {
                        startDetector()
                    }
                }
            }, 2000)
        }
    }

    private fun sendStatus(message: String) {
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra("message", message)
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SHAKTI AI Wake Word",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(
            this,
            WakeWordService::class.java
        ).setAction(ACTION_STOP)

        val pendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                Notification.Builder(this)
            }

        return builder
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("SHAKTI AI - SHAKTI")
            .setContentText("SHAKTI सुन रहा है")
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "बंद करें",
                pendingIntent
            )
            .build()
    }

    override fun onDestroy() {
        serviceActive = false
        detectorRunning = false

        try {
            audioRecord?.stop()
        } catch (_: Exception) {}

        destroyRecognizer()

        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}

        tts = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
