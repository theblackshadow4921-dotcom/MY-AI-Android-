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
import android.os.IBinder
import android.util.Log
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.k2fsa.sherpa.onnx.getKwsModelConfig
import com.k2fsa.sherpa.onnx.getKeywordsFile

class WakeWordService : Service() {

    companion object {
        const val ACTION_WAKE_WORD_DETECTED =
            "com.myai.assistant.WAKE_WORD_DETECTED"

        const val ACTION_STOP =
            "com.myai.assistant.STOP_WAKE_WORD"

        private const val TAG = "MYAI_WAKE"
        private const val CHANNEL_ID = "myai_wake_channel"
        private const val NOTIFICATION_ID = 1001
        private const val SAMPLE_RATE = 16000
    }

    @Volatile
    private var running = false

    @Volatile
    private var audioRecord: AudioRecord? = null

    private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Microphone permission missing")
            stopSelf()
            return START_NOT_STICKY
        }

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

        if (!running) {
            running = true
            worker = Thread {
                runDetector()
            }.also {
                it.start()
            }
        }

        return START_NOT_STICKY
    }

    private fun runDetector() {
        var spotter: KeywordSpotter? = null
        var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null
        var recorder: AudioRecord? = null

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
                throw IllegalStateException("Could not create KWS stream")
            }

            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT

            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                channelConfig,
                audioFormat
            )

            if (minBuffer <= 0) {
                throw IllegalStateException("Invalid microphone buffer")
            }

            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                channelConfig,
                audioFormat,
                maxOf(minBuffer, 3200) * 2
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("Microphone initialization failed")
            }

            audioRecord = recorder
            recorder.startRecording()

            Log.i(TAG, "Listening for HEY JARVIS")

            val buffer = ShortArray(1600)

            while (running) {
                val count = recorder.read(buffer, 0, buffer.size)

                if (count > 0) {
                    val samples = FloatArray(count) {
                        buffer[it] / 32768.0f
                    }

                    stream.acceptWaveform(
                        samples,
                        sampleRate = SAMPLE_RATE
                    )

                    while (running && spotter.isReady(stream)) {
                        spotter.decode(stream)

                        val result = spotter.getResult(stream)
                        val keyword = result.keyword

                        if (keyword.isNotBlank()) {
                            Log.i(TAG, "Detected: $keyword")

                            spotter.reset(stream)

                            val wakeIntent = Intent(
                                ACTION_WAKE_WORD_DETECTED
                            ).setPackage(packageName)

                            wakeIntent.putExtra("keyword", keyword)
                            sendBroadcast(wakeIntent)
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Wake word service error", e)

        } finally {
            running = false

            try {
                recorder?.let {
                    if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        it.stop()
                    }
                    it.release()
                }
            } catch (_: Exception) {
            }

            audioRecord = null

            try {
                stream?.release()
                spotter?.release()
            } catch (_: Exception) {
            }

            Log.i(TAG, "Wake word detector stopped")
            stopSelf()
        }
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
        val stopIntent = Intent(this, WakeWordService::class.java)
            .setAction(ACTION_STOP)

        val pendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
        running = false

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
