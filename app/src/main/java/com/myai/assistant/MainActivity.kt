package com.myai.assistant

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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(10, 18, 38))
            setPadding(16, 16, 16, 16)
        }

        val title = TextView(this).apply {
            text = "SHAKTI AI ASSISTANT"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(10, 18, 10, 18)
        }

        chatLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scroll = ScrollView(this)
        scroll.addView(chatLayout)

        val features = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        addFeatureButton(features, "📷", "Camera") {
            openCamera()
        }

        addFeatureButton(features, "🎤", "Voice Assistant") {
            openVoice()
        }

        addFeatureButton(features, "📍", "My Location") {
            getLocation()
        }

        addFeatureButton(features, "👤", "Select Contact") {
            openContacts()
        }

        addFeatureButton(features, "💬", "Send SMS") {
            sendSMS()
        }

        input = EditText(this).apply {
            hint = "अपना सवाल लिखें..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setSingleLine(true)
        }

        val send = Button(this).apply {
            text = "Send"
            setOnClickListener {
                val message = input.text.toString().trim()
                if (message.isNotEmpty()) {
                    addMessage("आप: $message")
                    input.text.clear()
                    askGemini(message)
                }
            }
        }

        root.addView(title)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        root.addView(features)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        bottom.addView(
            input,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        bottom.addView(send)

        root.addView(bottom)
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
            val intent = Intent("android.media.action.IMAGE_CAPTURE")
            startActivityForResult(intent, CAMERA_CODE)
        } catch (e: Exception) {
            addMessage("Camera उपलब्ध नहीं है।")
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

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "बोलिए...")
            startActivityForResult(intent, VOICE_CODE)
        } catch (e: Exception) {
            addMessage("Voice Recognition उपलब्ध नहीं है।")
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

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode != RESULT_OK || data == null) return

        when (requestCode) {
            CAMERA_CODE -> {
                val photo = data.extras?.get("data") as? Bitmap
                if (photo != null) {
                    val image = ImageView(this).apply {
                        setImageBitmap(photo)
                        adjustViewBounds = true
                    }
                    chatLayout.addView(image)
                    addMessage("फोटो प्राप्त हुई।")
                    analyzeImage(photo)
                } else {
                    addMessage("फोटो प्राप्त नहीं हुई।")
                }
            }

            VOICE_CODE -> {
                val result = data.getStringArrayListExtra(
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
                val uri = data.data
                if (uri != null) {
                    contentResolver.query(
                        uri,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                        null,
                        null,
                        null
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
            setTextColor(Color.WHITE)
            setPadding(12, 12, 12, 12)
        }
        chatLayout.addView(text)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(wakeWordReceiver)
        } catch (_: Exception) {
        }

        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
