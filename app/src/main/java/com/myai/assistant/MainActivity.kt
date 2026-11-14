package com.myai.assistant

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {

    private lateinit var chatLayout: LinearLayout
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(10, 18, 38))
            setPadding(20, 20, 20, 20)
        }

        val title = TextView(this).apply {
            text = "MY AI"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(10, 20, 10, 20)
        }

        chatLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scroll = ScrollView(this)
        scroll.addView(chatLayout)

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
                    addMessage("MY AI: आपका संदेश प्राप्त हुआ। Gemini कनेक्शन अगले चरण में जोड़ेंगे।")
                    input.text.clear()
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

        addMessage("MY AI में आपका स्वागत है!")
    }

    private fun addMessage(message: String) {
        val text = TextView(this).apply {
            this.text = message
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(12, 14, 12, 14)
        }
        chatLayout.addView(text)
    }
}
