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
            "com.myai.assistant.JARVIS_STATUS"
        const val ACTION_TRANSCRIPT =
            "com.myai.assistant.JARVIS_TRANSCRIPT"
        const val ACTION_REPLY =
            "com.myai.assistant.JARVIS_REPLY"
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
                            "JARVIS_LISTEN" -> startRecognitionNow()
                            "JARVIS_RESTART" -> startDetector()
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
                "JARVIS_LISTEN" -> startRecognitionNow()
                "JARVIS_RESTART" -> startDetector()
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

            Log.i(TAG, "Listening for HEY JARVIS")
            sendStatus("JARVIS चालू है। HEY JARVIS बोलें।")

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
                handler.post {
                    beginSpeechRecognition()
                }
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

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speakAndRestart(
                "सर, इस फोन में आवाज़ पहचान उपलब्ध नहीं है।"
            )
            return
        }

        speak(
            "जी सर, मैं सुन रहा हूँ। अब बोलिए।",
            "JARVIS_LISTEN"
        )
    }

    private fun startRecognitionNow() {
        if (!serviceActive) return

        try {
            destroyRecognizer()

            speechRecognizer =
                SpeechRecognizer.createSpeechRecognizer(
                    applicationContext
                )

            speechRecognizer?.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.i(TAG, "Speech recognition ready")
                    }

                    override fun onBeginningOfSpeech() {}

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        Log.e(
                            TAG,
                            "Speech recognition error: $error"
                        )
                        destroyRecognizer()
                        speakAndRestart(
                            "सर, आपकी आवाज़ समझ नहीं आई। फिर से HEY JARVIS बोलिए।"
                        )
                    }

                    override fun onResults(results: Bundle?) {
                        val spoken = results
                            ?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )
                            ?.firstOrNull()

                        destroyRecognizer()

                        if (spoken.isNullOrBlank()) {
                            speakAndRestart(
                                "सर, कृपया फिर से बोलिए।"
                            )
                        } else {
                            sendBroadcast(
                                Intent(ACTION_TRANSCRIPT)
                                    .setPackage(packageName)
                                    .putExtra("message", spoken)
                            )
                            askGemini(spoken)
                        }
                    }

                    override fun onPartialResults(
                        partialResults: Bundle?
                    ) {}

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?
                    ) {}
                }
            )

            val intent = Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    "hi-IN"
                )
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                    "hi-IN"
                )
                putExtra(
                    RecognizerIntent.EXTRA_MAX_RESULTS,
                    1
                )
                putExtra(
                    RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                    false
                )
                putExtra(
                    RecognizerIntent.EXTRA_CALLING_PACKAGE,
                    packageName
                )
            }

            speechRecognizer?.startListening(intent)

        } catch (e: Exception) {
            Log.e(TAG, "Speech recognition start failed", e)
            destroyRecognizer()
            speakAndRestart(
                "सर, आवाज़ पहचान शुरू नहीं हो सकी।"
            )
        }
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}

        speechRecognizer = null
    }

    private fun askGemini(question: String) {
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
        speak(message, "JARVIS_RESTART")
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
                    if (utteranceId == "JARVIS_LISTEN") {
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
                "MY AI Wake Word",
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
            .setContentTitle("MY AI - JARVIS")
            .setContentText("HEY JARVIS सुन रहा है")
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
