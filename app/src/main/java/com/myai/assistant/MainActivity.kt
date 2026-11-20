package com.myai.assistant
import java.io.File
import android.app.AlertDialog
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.core.content.FileProvider
import android.provider.MediaStore
import android.graphics.BitmapFactory

import java.io.ByteArrayOutputStream
import android.util.Base64

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.location.LocationManager
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.net.Uri
import java.util.Locale
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val wakeWordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra("message") ?: return

            when (intent.action) {
                WakeWordService.ACTION_STATUS ->
                    addMessage(message)

                WakeWordService.ACTION_TRANSCRIPT ->
                    addMessage("आपने कहा: $message")

                WakeWordService.ACTION_REPLY ->
                    addMessage("SHAKTI AI: $message")
            }
        }
    }

    private lateinit var chatLayout: LinearLayout
    private lateinit var input: EditText
    private var tts: TextToSpeech? = null
    private var selectedPhone = ""
    private var cameraPhotoUri: Uri? = null
    private var cameraPhotoPath: String? = null
    private var speechRecognizer: SpeechRecognizer? = null

    companion object {
        const val CAMERA_CODE = 101
        const val VOICE_CODE = 102
        const val CONTACT_CODE = 103
        const val LOCATION_CODE = 104
        const val WAKE_PERMISSION_CODE = 106
        const val MIC_PERMISSION_CODE = 105
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cameraPrefs = getSharedPreferences("shakti_camera", MODE_PRIVATE)
        cameraPhotoUri = savedInstanceState?.getString("pending_camera_uri")
            ?.let { Uri.parse(it) }
            ?: cameraPrefs.getString("pending_camera_uri", null)?.let { Uri.parse(it) }
        cameraPhotoPath = savedInstanceState?.getString("pending_camera_path")
            ?: cameraPrefs.getString("pending_camera_path", null)

        val wakeFilter = IntentFilter().apply {
            addAction(WakeWordService.ACTION_STATUS)
            addAction(WakeWordService.ACTION_TRANSCRIPT)
            addAction(WakeWordService.ACTION_REPLY)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(wakeWordReceiver, wakeFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(wakeWordReceiver, wakeFilter)
        }

        tts = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                tts?.language = Locale("hi", "IN")
            }
        }


        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val blue = Color.rgb(37, 99, 235)
        val navy = Color.rgb(20, 43, 78)
        val muted = Color.rgb(115, 132, 155)
        val white = Color.WHITE

        fun rounded(color: Int, radius: Int = 18): android.graphics.drawable.GradientDrawable {
            return android.graphics.drawable.GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(radius).toFloat()
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 248, 253))
            setPadding(dp(14), dp(10), dp(14), dp(8))
        }

        // PREMIUM HEADER
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(12))
        }

        val menu = TextView(this).apply {
            text = "☰"
            textSize = 25f
            setTextColor(navy)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(12), dp(4))
            setOnClickListener {
                android.widget.Toast.makeText(
                    this@MainActivity, "SHAKTI AI Menu",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
        header.addView(menu)

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        brand.addView(TextView(this).apply {
            text = "SHAKTI AI"
            textSize = 21f
            setTextColor(navy)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        brand.addView(TextView(this).apply {
            text = "PERSONAL AI ASSISTANT"
            textSize = 9f
            letterSpacing = 1.2f
            setTextColor(muted)
        })

        header.addView(
            brand,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        val online = TextView(this).apply {
            text = "● Online"
            textSize = 11f
            setTextColor(Color.rgb(22, 150, 91))
            setPadding(dp(9), dp(7), dp(9), dp(7))
            background = rounded(Color.rgb(226, 249, 238), 30)
        }
        header.addView(online)
        root.addView(header)

        // MAIN SCROLL CONTENT
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(12))
        }

        // HERO / ROBOT PANEL
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(20), dp(16), dp(20))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.rgb(30, 91, 190),
                    Color.rgb(75, 105, 220),
                    Color.rgb(104, 91, 205)
                )
            ).apply {
                cornerRadius = dp(25).toFloat()
            }
        }

        val robot = TextView(this).apply {
            text = "🤖"
            textSize = 67f
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(255, 255, 255), 60)
        }
        hero.addView(
            robot,
            LinearLayout.LayoutParams(dp(112), dp(112))
        )

        hero.addView(TextView(this).apply {
            text = "SHAKTI AI"
            textSize = 27f
            setTextColor(white)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(2))
        })

        hero.addView(TextView(this).apply {
            text = "सोच से समाधान तक..."
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })

        hero.addView(TextView(this).apply {
            text = "मुझे Divyansh Sir ने बनाया है।"
            textSize = 12f
            setTextColor(Color.rgb(225, 235, 255))
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, 0)
        })

        content.addView(
            hero,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(245)
            )
        )

        // FEATURE SECTION
        val featureHeading = TextView(this).apply {
            text = "आपके AI Tools"
            textSize = 19f
            setTextColor(navy)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(3), dp(19), 0, dp(8))
        }
        content.addView(featureHeading)

        fun featureCard(
            emoji: String,
            heading: String,
            detail: String,
            action: () -> Unit
        ): LinearLayout {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(8), dp(10))
                background = rounded(white, 18)
                elevation = dp(2).toFloat()
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }

            card.addView(TextView(this).apply {
                text = emoji
                textSize = 25f
            })

            card.addView(TextView(this).apply {
                text = heading
                textSize = 13f
                setTextColor(navy)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, dp(5), 0, dp(2))
            })

            card.addView(TextView(this).apply {
                text = detail
                textSize = 10f
                setTextColor(muted)
            })

            return card
        }

        fun addFeatureRow(
            first: LinearLayout,
            second: LinearLayout? = null
        ) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val lp1 = LinearLayout.LayoutParams(0, dp(112), 1f)
            lp1.setMargins(dp(3), dp(4), dp(5), dp(5))
            row.addView(first, lp1)

            if (second != null) {
                val lp2 = LinearLayout.LayoutParams(0, dp(112), 1f)
                lp2.setMargins(dp(5), dp(4), dp(3), dp(5))
                row.addView(second, lp2)
            } else {
                row.addView(
                    View(this),
                    LinearLayout.LayoutParams(0, dp(112), 1f)
                )
            }

            content.addView(row)
        }

        addFeatureRow(
            featureCard("📷", "Camera", "Photo analysis") { openCamera() },
            featureCard("🎤", "Voice Assistant", "बोलकर पूछें") { openVoice() }
        )

        addFeatureRow(
            featureCard("📍", "My Location", "अपनी लोकेशन") { getLocation() },
            featureCard("👤", "Select Contact", "Contact चुनें") { openContacts() }
        )

        addFeatureRow(
            featureCard("💬", "Send SMS", "Message भेजें") { sendSMS() }
        )

        // CHAT SECTION
        content.addView(TextView(this).apply {
            text = "SHAKTI से बातचीत"
            textSize = 19f
            setTextColor(navy)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(3), dp(18), 0, dp(8))
        })

        val chatCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(white, 20)
            elevation = dp(2).toFloat()
        }

        chatLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        chatCard.addView(
            chatLayout,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        content.addView(
            chatCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(2), 0, dp(2), dp(10))
            }
        )

        scroll.addView(content)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        // MESSAGE INPUT
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(7), dp(5), dp(7))
            background = rounded(white, 22)
            elevation = dp(3).toFloat()
        }

        input = EditText(this).apply {
            hint = "SHAKTI से कुछ पूछें..."
            textSize = 14f
            setTextColor(navy)
            setHintTextColor(muted)
            setSingleLine(true)
            setPadding(dp(12), dp(10), dp(8), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(247, 249, 253))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.rgb(225, 232, 243))
            }
        }

        val send = Button(this).apply {
            text = "Send"
            textSize = 13f
            isAllCaps = false
            setTextColor(white)
            backgroundTintList =
                android.content.res.ColorStateList.valueOf(blue)

            setOnClickListener {
                val message = input.text.toString().trim()
                if (message.isNotEmpty()) {
                    addMessage("आप: $message")
                    input.text.clear()
                    askGemini(message)
                }
            }
        }

        bottom.addView(
            input,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )
        bottom.addView(
            send,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)
            ).apply {
                setMargins(dp(7), 0, 0, 0)
            }
        )

        root.addView(bottom)

        // BOTTOM NAVIGATION
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, dp(2))
        }

        fun navItem(
            icon: String,
            label: String,
            action: () -> Unit
        ): TextView {
            return TextView(this).apply {
                text = "$icon\n$label"
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(blue)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
        }

        nav.addView(
            navItem("⌂", "Home") {
                scroll.smoothScrollTo(0, 0)
            },
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        nav.addView(
            navItem("◷", "History") {
                addMessage("आपकी बातचीत इसी Chat Section में दिखाई देती है।")
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            },
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        nav.addView(
            navItem("✦", "Premium") {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "Premium Plans जल्द उपलब्ध होंगे।",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            },
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        nav.addView(
            navItem("ⓘ", "About") {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("SHAKTI AI")
                    .setMessage(
                        "आपका Personal AI Assistant\n\n" +
                        "सोच से समाधान तक...\n" +
                        "Created by Divyansh Sir"
                    )
                    .setPositiveButton("OK", null)
                    .show()
            },
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        root.addView(nav)
        setContentView(root)


        addMessage("नमस्ते! SHAKTI AI में आपका स्वागत है।")
        startWakeWordIfPermitted()

    }


    private fun startWakeWordIfPermitted() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                WAKE_PERMISSION_CODE
            )
            return
        }

        try {
            val serviceIntent = Intent(this, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            addMessage("SHAKTI सक्रिय हो रहा है। सिर्फ SHAKTI बोलें।")
        } catch (e: Exception) {
            addMessage("Wake Word Service शुरू नहीं हो सकी: ${e.message}")
        }
    }

    private fun openAppByVoice(command: String): Boolean {
        val text = command.lowercase(Locale.ROOT)

        if (text.contains("youtube") || text.contains("यूट्यूब")) {
            val launchIntent = packageManager.getLaunchIntentForPackage("com.google.android.youtube")
            if (launchIntent != null) {
                startActivity(launchIntent)
                addMessage("जी सर, YouTube खोल रहा हूँ।")
            } else {
                addMessage("सर, YouTube ऐप इस फोन में नहीं मिला।")
            }
            return true
        }

        return false
    }

    private fun askGemini(question: String) {
        if (openAppByVoice(question)) {
            tts?.speak("जी सर, YouTube खोल रहा हूँ।", TextToSpeech.QUEUE_FLUSH, null, "MY_AI_COMMAND")
            return
        }

        addMessage("SHAKTI AI: जवाब तैयार हो रहा है...")

        thread {
            var connection: HttpURLConnection? = null

            val reply = try {
                connection = URL("https://my-ai-gemini.onrender.com/chat")
                    .openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 15000
                connection.readTimeout = 120000
                connection.setRequestProperty(
                    "Content-Type", "application/json"
                )

                val body = JSONObject()
                    .put("message", question).toString()

                connection.outputStream.use {
                    it.write(body.toByteArray(Charsets.UTF_8))
                }

                val stream = if (connection.responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val response = stream.bufferedReader().use {
                    it.readText()
                }

                JSONObject(response).optString(
                    "reply", "Gemini से जवाब नहीं मिला।"
                )
            } catch (e: Exception) {
                "कनेक्शन में समस्या: ${e.message}"
            } finally {
                connection?.disconnect()
            }

            runOnUiThread {
                addMessage("SHAKTI AI: $reply")
                tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "MY_AI_REPLY")
            }
        }
    }

    private fun analyzeImage(photo: Bitmap) {
        addMessage("SHAKTI AI: फोटो का विश्लेषण कर रहा हूँ...")

        thread {
            var connection: HttpURLConnection? = null

            val reply = try {
                val output = ByteArrayOutputStream()
                photo.compress(Bitmap.CompressFormat.JPEG, 65, output)

                val encodedImage = Base64.encodeToString(
                    output.toByteArray(),
                    Base64.NO_WRAP
                )

                connection = URL(
                    "https://my-ai-gemini.onrender.com/analyze-image"
                ).openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 20000
                connection.readTimeout = 120000
                connection.setRequestProperty(
                    "Content-Type", "application/json"
                )

                val body = JSONObject()
                    .put("message", "इस फोटो में क्या दिखाई दे रहा है? हिंदी में विस्तार से बताओ।")
                    .put("image", encodedImage)
                    .put("mime_type", "image/jpeg")
                    .toString()

                connection.outputStream.use {
                    it.write(body.toByteArray(Charsets.UTF_8))
                }

                val stream = if (connection.responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val response = stream.bufferedReader().use {
                    it.readText()
                }

                JSONObject(response).optString(
                    "reply", "फोटो का जवाब नहीं मिला।"
                )
            } catch (e: Exception) {
                "फोटो भेजने में समस्या: ${e.message}"
            } finally {
                connection?.disconnect()
            }

            runOnUiThread {
                addMessage("SHAKTI AI: $reply")
                tts?.speak(
                    reply,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "MY_AI_IMAGE_REPLY"
                )
            }
        }
    }

    private fun addFeatureButton(
        parent: LinearLayout,
        icon: String,
        description: String,
        action: () -> Unit
    ) {
        val density = resources.displayMetrics.density

        val button = Button(this).apply {
            text = icon
            textSize = 20f
            setTextColor(Color.rgb(37, 99, 235))
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.WHITE
            )
            contentDescription = description
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minHeight = 0
            setOnClickListener { action() }
        }

        val params = LinearLayout.LayoutParams(
            0, (48 * density).toInt(), 1f
        )
        params.setMargins(
            (3 * density).toInt(), (4 * density).toInt(),
            (3 * density).toInt(), (4 * density).toInt()
        )
        parent.addView(button, params)
    }

    private fun openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_CODE
            )
            return
        }

        launchCamera()
    }

    private fun launchCamera() {
        try {
            val cameraDir = File(cacheDir, "camera")
            if (!cameraDir.exists() && !cameraDir.mkdirs()) {
                throw IllegalStateException("Camera cache नहीं बन पाया")
            }

            val photoFile = File.createTempFile(
                "SHAKTI_${System.currentTimeMillis()}_", ".jpg", cameraDir
            )
            val uri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", photoFile
            )

            cameraPhotoUri = uri
            cameraPhotoPath = photoFile.absolutePath

            getSharedPreferences("shakti_camera", MODE_PRIVATE)
                .edit()
                .putString("pending_camera_uri", uri.toString())
                .putString("pending_camera_path", photoFile.absolutePath)
                .commit()

            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(
                    contentResolver, "SHAKTI camera photo", uri
                )
            }

            val handlers = packageManager.queryIntentActivities(
                intent, PackageManager.MATCH_DEFAULT_ONLY
            )
            if (handlers.isEmpty()) {
                throw IllegalStateException("Camera ऐप नहीं मिला")
            }

            handlers.forEach {
                grantUriPermission(
                    it.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }

            startActivityForResult(intent, CAMERA_CODE)
        } catch (e: Exception) {
            cameraPhotoUri = null
            cameraPhotoPath = null
            addMessage("Camera शुरू नहीं हो सका: ${e.message}")
        }
    }

        private fun decodeCameraPhotoFile(path: String): Bitmap? {
        return try {
            val file = File(path)
            if (!file.exists() || file.length() <= 0L) {
                android.util.Log.e("SHAKTI_CAMERA", "Photo file missing or empty: $path")
                return null
            }

            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, bounds)

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                android.util.Log.e("SHAKTI_CAMERA", "Invalid image dimensions")
                return null
            }

            var sample = 1
            while (bounds.outWidth / sample > 2048 ||
                   bounds.outHeight / sample > 2048) {
                sample *= 2
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(path, options)
        } catch (e: Exception) {
            android.util.Log.e("SHAKTI_CAMERA", "File decode failed", e)
            null
        }
    }

    private fun decodeCameraPhoto(uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            } ?: return null

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null
            }

            var sample = 1
            while (bounds.outWidth / sample > 2048 ||
                   bounds.outHeight / sample > 2048) {
                sample *= 2
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (e: Exception) {
            android.util.Log.e("SHAKTI_CAMERA", "Photo decode failed", e)
            null
        }
    }

    private fun openVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                MIC_PERMISSION_CODE
            )
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            addMessage("इस फोन में Speech Recognition सेवा उपलब्ध नहीं है।")
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

            val status = TextView(this).apply {
                text = "🎙️  SHAKTI सुन रही है...\n\nअब हिंदी में बोलिए।"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(28, 36, 28, 36)
            }

            val dialog = AlertDialog.Builder(this)
                .setTitle("SHAKTI AI")
                .setView(status)
                .setNegativeButton("रद्द करें") { _, _ ->
                    speechRecognizer?.cancel()
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                }
                .create()

            var lastPartialText = ""

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    android.util.Log.d("SHAKTI_VOICE", "Ready for speech")
                    status.text = "🎙️  SHAKTI सुन रही है...\n\nबोलना शुरू कीजिए।"
                }

                override fun onBeginningOfSpeech() {
                    android.util.Log.d("SHAKTI_VOICE", "Beginning of speech detected")
                    status.text = "🎙️  आपकी आवाज़ सुन रही हूँ..."
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    status.text = "⌛ आपकी बात समझ रही हूँ..."
                }

                override fun onError(error: Int) {
                    val errorName = when (error) {
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
                        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
                        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
                        SpeechRecognizer.ERROR_SERVER -> "SERVER"
                        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
                        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "MIC_PERMISSION"
                        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
                        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
                        else -> "UNKNOWN"
                    }
                    android.util.Log.e("SHAKTI_VOICE", "Speech error: $error ($errorName), partial=$lastPartialText")
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                    if (dialog.isShowing) dialog.dismiss()

                    if (error == SpeechRecognizer.ERROR_NO_MATCH &&
                        lastPartialText.isNotBlank()
                    ) {
                        input.setText(lastPartialText)
                        addMessage("आपने कहा: $lastPartialText")
                        askGemini(lastPartialText)
                    } else {
                        addMessage("SHAKTI Voice Error: $error ($errorName). कृपया थोड़ा साफ और धीरे बोलकर दोबारा कोशिश करें।")
                    }
                }

                override fun onResults(results: Bundle?) {
                    val recognizedList = results?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )
                    val spoken = recognizedList?.firstOrNull()
                    android.util.Log.d("SHAKTI_VOICE", "Final=$recognizedList partial=$lastPartialText keys=${results?.keySet()}")

                    speechRecognizer?.destroy()
                    speechRecognizer = null
                    if (dialog.isShowing) dialog.dismiss()

                    if (!spoken.isNullOrBlank()) {
                        input.setText(spoken)
                        addMessage("आपने कहा: $spoken")
                        askGemini(spoken)
                    } else {
                        val detail = if (lastPartialText.isNotBlank()) { "शुरुआती पहचान: $lastPartialText" } else { "कोई शुरुआती टेक्स्ट भी नहीं मिला।" }
                        android.util.Log.e("SHAKTI_VOICE", "EMPTY_RESULTS: $detail")
                        addMessage("आवाज़ का अंतिम टेक्स्ट खाली मिला। $detail")
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )?.firstOrNull()
                    if (!partial.isNullOrBlank()) {
                        lastPartialText = partial
                        android.util.Log.d("SHAKTI_VOICE", "Partial result: $partial")
                        status.text = "🎙️  $partial"
                    } else {
                        android.util.Log.d("SHAKTI_VOICE", "Partial result is empty")
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            dialog.setOnDismissListener {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                speechRecognizer = null
            }
            dialog.show()

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")\n                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    1500L
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    1000L
                )
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            speechRecognizer?.destroy()
            speechRecognizer = null
            addMessage("Voice Assistant शुरू नहीं हो सका: ${e.message}")
        }
    }

        private fun getLocation() {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (fine != PackageManager.PERMISSION_GRANTED &&
            coarse != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_CODE
            )
            return
        }

        try {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            val location = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            if (location != null) {
                addMessage(
                    "आपकी आखिरी उपलब्ध लोकेशन:\nLatitude: ${location.latitude}\nLongitude: ${location.longitude}"
                )
            } else {
                addMessage("अभी लोकेशन उपलब्ध नहीं है। GPS चालू करके दोबारा कोशिश करें।")
            }
        } catch (e: SecurityException) {
            addMessage("Location permission नहीं मिली।")
        } catch (e: Exception) {
            addMessage("Location प्राप्त नहीं हो सकी।")
        }
    }

    private fun openContacts() {
        try {
            val intent = Intent(
                Intent.ACTION_PICK,
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            )
            startActivityForResult(intent, CONTACT_CODE)
        } catch (e: Exception) {
            addMessage("Contact Picker उपलब्ध नहीं है।")
        }
    }

    private fun sendSMS() {
        if (selectedPhone.isBlank()) {
            addMessage("पहले Select Contact दबाकर नंबर चुनें।")
            return
        }

        val message = input.text.toString().trim()
        if (message.isBlank()) {
            addMessage("पहले SMS का संदेश लिखें।")
            return
        }

        val intent = Intent(
            Intent.ACTION_SENDTO,
            Uri.parse("smsto:${Uri.encode(selectedPhone)}")
        )
        intent.putExtra("sms_body", message)

        try {
            startActivity(intent)
        } catch (e: Exception) {
            addMessage("SMS ऐप नहीं खुल सका।")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        cameraPhotoUri?.let {
            outState.putString("pending_camera_uri", it.toString())
        }
        cameraPhotoPath?.let {
            outState.putString("pending_camera_path", it)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode != RESULT_OK) {
            if (requestCode == CAMERA_CODE) {
                cameraPhotoUri = null
                cameraPhotoPath = null
                getSharedPreferences("shakti_camera", MODE_PRIVATE)
                    .edit()
                    .remove("pending_camera_uri")
                    .remove("pending_camera_path")
                    .apply()
            }
            return
        }

        when (requestCode) {
            CAMERA_CODE -> {
                val uri = cameraPhotoUri
                    ?: getSharedPreferences("shakti_camera", MODE_PRIVATE)
                        .getString("pending_camera_uri", null)?.let { Uri.parse(it) }
                val photoPath = cameraPhotoPath
                    ?: getSharedPreferences("shakti_camera", MODE_PRIVATE)
                        .getString("pending_camera_path", null)
                val photo = photoPath?.let { decodeCameraPhotoFile(it) }
                    ?: uri?.let { decodeCameraPhoto(it) }
                    ?: data?.data?.let { decodeCameraPhoto(it) }
                    ?: (data?.extras?.get("data") as? Bitmap)

                cameraPhotoUri = null
                cameraPhotoPath = null
                getSharedPreferences("shakti_camera", MODE_PRIVATE)
                    .edit()
                    .remove("pending_camera_uri")
                    .remove("pending_camera_path")
                    .apply()

                if (photo != null) {
                    val image = ImageView(this).apply {
                        setImageBitmap(photo)
                        adjustViewBounds = true
                        maxHeight = (240 * resources.displayMetrics.density).toInt()
                    }
                    chatLayout.addView(image)
                    addMessage("फोटो प्राप्त हुई। Gemini विश्लेषण कर रहा है।")
                    analyzeImage(photo)
                } else {
                    addMessage("फोटो प्राप्त नहीं हुई। दोबारा कोशिश करें।")
                }
            }

            VOICE_CODE -> {
                val result = data?.getStringArrayListExtra(
                    RecognizerIntent.EXTRA_RESULTS
                )
                val spoken = result?.firstOrNull()
                if (!spoken.isNullOrBlank()) {
                    input.setText(spoken)
                    addMessage("आपने कहा: $spoken")
                    askGemini(spoken)
                }
            }

            CONTACT_CODE -> {
                val uri = data?.data
                if (uri != null) {
                    contentResolver.query(
                        uri,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                        null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            selectedPhone = cursor.getString(0) ?: ""
                            addMessage("Contact चुना गया: $selectedPhone")
                        }
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == LOCATION_CODE) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                getLocation()
            } else {
                addMessage("Location permission अस्वीकार कर दी गई।")
            }
        } else if (requestCode == WAKE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                startWakeWordIfPermitted()
            } else {
                addMessage("SHAKTI के लिए Microphone permission जरूरी है।")
            }
        } else if (requestCode == MIC_PERMISSION_CODE) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                openVoice()
            } else {
                addMessage("Microphone permission अस्वीकार कर दी गई।")
            }
        } else if (requestCode == CAMERA_CODE) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                launchCamera()
            } else {
                addMessage("Camera permission अस्वीकार कर दी गई।")
            }
        }
    }

    private fun addMessage(message: String) {
        val text = TextView(this).apply {
            this.text = message
            textSize = 16f
            setTextColor(Color.rgb(35, 52, 78))
            setPadding(16, 13, 16, 13)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 18f * resources.displayMetrics.density
                setStroke(1, Color.rgb(225, 233, 244))
            }
        }
        chatLayout.addView(text)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(wakeWordReceiver)
        } catch (_: Exception) {
        }

        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null

        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
