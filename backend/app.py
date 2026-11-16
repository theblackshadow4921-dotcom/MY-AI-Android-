import os
import requests
from flask import Flask, request, jsonify
from flask_limiter import Limiter
from flask_limiter.util import get_remote_address

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 16 * 1024

limiter = Limiter(
    get_remote_address,
    app=app,
    default_limits=[],
    storage_uri="memory://"
)

MODEL = "gemini-3.5-flash-lite"

@app.route("/", methods=["GET"])
def home():
    return jsonify({"status": "MY AI backend online"})

@app.route("/chat", methods=["POST"])
@limiter.limit("10 per minute")
def chat():
    api_key = os.getenv("GEMINI_API_KEY")

    if not api_key:
        return jsonify({"reply": "Server configuration error."}), 503

    data = request.get_json(silent=True) or {}
    question = str(data.get("message", "")).strip()

    if not question or len(question) > 10000:
        return jsonify({"reply": "सवाल खाली है या बहुत बड़ा है।"}), 400

    url = (
        "https://generativelanguage.googleapis.com/v1beta/models/"
        + MODEL + ":generateContent"
    )

    payload = {
        "contents": [{"parts": [{"text": question}]}]
    }

    try:
        response = requests.post(
            url,
            headers={"x-goog-api-key": api_key},
            json=payload,
            timeout=(20, 120)
        )

        if not response.ok:
            return jsonify({"reply": "Gemini सेवा में समस्या हुई।"}), 502

        result = response.json()
        answer = result["candidates"][0]["content"]["parts"][0]["text"]
        return jsonify({"reply": answer})

    except (requests.RequestException, KeyError, IndexError, ValueError):
        return jsonify({"reply": "अभी जवाब नहीं मिल पाया। दोबारा कोशिश करें।"}), 503
