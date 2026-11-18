import os
import base64
import requests
from flask import Flask, request, jsonify
from flask_limiter import Limiter
from flask_limiter.util import get_remote_address

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 8 * 1024 * 1024

limiter = Limiter(
    get_remote_address,
    app=app,
    default_limits=[],
    storage_uri="memory://"
)

MODEL = "gemini-3.5-flash-lite"

def ask_gemini(payload, use_search=False):
    payload["systemInstruction"] = {
        "parts": [{
            "text": "तुम MY AI नाम के JARVIS जैसे हिंदी AI सहायक हो। उपयोगकर्ता को हमेशा सर कहकर संबोधित करो। कभी भाई या दोस्त मत कहो। हमेशा स्वाभाविक, स्पष्ट हिंदी देवनागरी लिपि में जवाब दो। जवाब संक्षिप्त और उपयोगी रखो। उपयोगकर्ता का नाम तभी बताओ जब उसने स्वयं बताया हो; अनुमान मत लगाओ। फोटो का विश्लेषण भी हिंदी में करो।"
        }]
    }

    if use_search:
        payload["tools"] = [{"google_search": {}}]

    api_key = os.getenv("GEMINI_API_KEY")

    if not api_key:
        return None, 503

    url = (
        "https://generativelanguage.googleapis.com/v1beta/models/"
        + MODEL + ":generateContent"
    )

    try:
        response = requests.post(
            url,
            headers={"x-goog-api-key": api_key},
            json=payload,
            timeout=(20, 120)
        )

        if response.status_code == 429 and use_search:
            app.logger.warning(
                "Google Search quota unavailable. Retrying without Search."
            )
            fallback_payload = dict(payload)
            fallback_payload.pop("tools", None)
            response = requests.post(
                url,
                headers={"x-goog-api-key": api_key},
                json=fallback_payload,
                timeout=(20, 120)
            )

        if not response.ok:
            app.logger.error(
                "Gemini API HTTP %s: %s",
                response.status_code,
                response.text[:2000]
            )
            return None, 502

        result = response.json()
        candidate = result["candidates"][0]
        parts = candidate["content"]["parts"]
        answer = "".join(part.get("text", "") for part in parts)

        sources = []
        seen = set()
        metadata = candidate.get("groundingMetadata", {})
        for chunk in metadata.get("groundingChunks", []):
            web = chunk.get("web", {})
            uri = web.get("uri")
            title = web.get("title", "वेबसाइट")
            if uri and uri not in seen:
                seen.add(uri)
                sources.append(f"- {title}: {uri}")

        if sources:
            answer += "\n\nजानकारी के स्रोत:\n" + "\n".join(sources)

        return answer, 200

    except (requests.RequestException, KeyError, IndexError, ValueError) as exc:
        app.logger.exception("Gemini request failed: %s", exc)
        return None, 503


@app.route("/", methods=["GET"])
def home():
    return jsonify({"status": "MY AI backend online"})


@app.route("/chat", methods=["POST"])
@limiter.limit("10 per minute")
def chat():
    data = request.get_json(silent=True) or {}
    question = str(data.get("message", "")).strip()

    if not question or len(question) > 10000:
        return jsonify({"reply": "सवाल खाली है या बहुत बड़ा है।"}), 400

    answer, status = ask_gemini(
        {"contents": [{"parts": [{"text": question}]}]},
        use_search=True
    )

    if answer is None:
        return jsonify({"reply": "Gemini सेवा में समस्या हुई।"}), status

    return jsonify({"reply": answer})


@app.route("/analyze-image", methods=["POST"])
@limiter.limit("5 per minute")
def analyze_image():
    data = request.get_json(silent=True) or {}

    question = str(
        data.get("message", "इस फोटो में क्या दिखाई दे रहा है? हिंदी में बताओ।")
    ).strip()

    image_data = data.get("image", "")
    mime_type = data.get("mime_type", "image/jpeg")

    if not isinstance(image_data, str) or not image_data:
        return jsonify({"reply": "फोटो प्राप्त नहीं हुई।"}), 400

    if mime_type not in ("image/jpeg", "image/png", "image/webp"):
        return jsonify({"reply": "यह फोटो फॉर्मेट समर्थित नहीं है।"}), 400

    if len(image_data) > 7 * 1024 * 1024:
        return jsonify({"reply": "फोटो बहुत बड़ी है। छोटी फोटो भेजें।"}), 413

    try:
        image_bytes = base64.b64decode(image_data, validate=True)
    except Exception:
        return jsonify({"reply": "फोटो डेटा सही नहीं है।"}), 400

    if not image_bytes:
        return jsonify({"reply": "फोटो खाली है।"}), 400

    payload = {
        "contents": [{
            "parts": [
                {"text": question or "इस फोटो का हिंदी में वर्णन करो।"},
                {
                    "inline_data": {
                        "mime_type": mime_type,
                        "data": base64.b64encode(image_bytes).decode("utf-8")
                    }
                }
            ]
        }]
    }

    answer, status = ask_gemini(payload)

    if answer is None:
        return jsonify({"reply": "फोटो का विश्लेषण नहीं हो पाया। दोबारा कोशिश करें।"}), status

    return jsonify({"reply": answer})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 5000)))
