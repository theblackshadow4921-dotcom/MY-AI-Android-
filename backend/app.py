import os
import base64
import requests
from flask import Flask, request, jsonify
from flask_limiter import Limiter
from flask_limiter.util import get_remote_address

try:
    from .market_rates import fetch_mandi_prices, normalize_commodity, commodity_list
except ImportError:
    from market_rates import fetch_mandi_prices, normalize_commodity, commodity_list


app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 8 * 1024 * 1024

limiter = Limiter(
    get_remote_address,
    app=app,
    default_limits=[],
    storage_uri=os.getenv("REDIS_URL", "").strip() or "memory://"
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





@app.route("/market-rates", methods=["GET"])
@limiter.limit("20 per minute")
def market_rates():
    commodity = request.args.get("commodity", "").strip() or None
    category = request.args.get("category", "").strip().casefold() or None
    state = request.args.get("state", "").strip() or None
    district = request.args.get("district", "").strip() or None
    market = request.args.get("market", "").strip() or None
    commodity_offset = request.args.get("commodity_offset", default=0, type=int)
    commodity_limit = request.args.get("commodity_limit", default=12, type=int)

    for value in (commodity, category, state, district, market):
        if value and len(value) > 100:
            return jsonify({"ok": False, "error": "खोज का नाम बहुत लंबा है।"}), 400

    if commodity_offset < 0 or commodity_offset > 10000:
        return jsonify({"ok": False, "error": "commodity_offset अमान्य है।"}), 400

    if commodity_limit < 1 or commodity_limit > 15:
        return jsonify({"ok": False, "error": "commodity_limit 1 से 15 के बीच होना चाहिए।"}), 400

    result = fetch_mandi_prices(
        commodity=commodity,
        category=category,
        state=state,
        district=district,
        market=market,
        limit=100,
        commodity_offset=commodity_offset,
        commodity_limit=commodity_limit
    )

    if not result.get("ok"):
        return jsonify(result), 502
    return jsonify(result)


@app.route("/chat", methods=["POST"])
@limiter.limit("10 per minute")
def chat():
    data = request.get_json(silent=True) or {}
    question = str(data.get("message", "")).strip()

    if not question or len(question) > 10000:
        return jsonify({"reply": "सवाल खाली है या बहुत बड़ा है।"}), 400

    mandi_context = ""
    q = question.casefold()

    price_words = (
        "भाव", "रेट", "दाम", "मंडी", "कीमत", "मूल्य",
        "price", "rate", "bhav", "mandi"
    )
    category_words = {
        "vegetables": ("सब्जी", "सब्जियां", "सब्जियों", "vegetables", "all vegetables"),
        "fruits": ("फल", "फलों", "फलो", "fruits", "all fruits"),
        "cereals": ("अनाज", "गेहूं", "गेहूँ", "चावल", "धान", "मक्का", "फसल", "cereals", "grains"),
        "pulses": ("दाल", "दालें", "दालों", "चना", "अरहर", "मसूर", "मूंग", "उड़द", "pulses"),
        "spices": ("मसाला", "मसाले", "मसालों", "हल्दी", "धनिया", "spices"),
    }

    is_market_question = any(word in q for word in price_words)
    if is_market_question:
        try:
            commodities = commodity_list()
            matched = normalize_commodity(question, commodities)
            result = None

            if matched:
                result = fetch_mandi_prices(
                    commodity=matched["cmdt_name"], limit=120
                )
            else:
                selected_category = None
                for category, words in category_words.items():
                    if any(word in q for word in words):
                        selected_category = category
                        break

                if selected_category:
                    result = fetch_mandi_prices(
                        category=selected_category, limit=120
                    )

            if result and result.get("ok"):
                records = result.get("records", [])
                mandi_context = (
                    "\n\nसरकारी AGMARKNET मंडी डेटा:\n"
                    + str(records[:120])
                    + "\n\nउत्तर देने के अनिवार्य नियम:"
                    + "\n1. केवल दिए गए सरकारी रिकॉर्ड के आधार पर भाव बताएं।"
                    + "\n2. हर भाव के साथ मंडी और रिकॉर्ड की तारीख बताएं।"
                    + "\n3. जहां उपलब्ध हो, प्रति क्विंटल और प्रति किलो दोनों भाव बताएं।"
                    + "\n4. क्विंटल से किलो का हिसाब: प्रति क्विंटल भाव को 100 से भाग दें।"
                    + "\n5. जिस इकाई का डेटा उपलब्ध नहीं है, उसका अनुमान न लगाएं।"
                    + "\n6. पुराने रिकॉर्ड को आज का भाव न बताएं।"
                    + "\n7. रिकॉर्ड खाली हो तो साफ बताएं कि उपलब्ध रिकॉर्ड नहीं मिला।"
                    + "\n8. यह थोक मंडी भाव है, खुदरा दुकान का भाव नहीं।"
                )
            elif result:
                mandi_context = (
                    "\n\nमंडी डेटा उपलब्ध नहीं: "
                    + result.get("error", "अज्ञात त्रुटि")
                )
            else:
                mandi_context = (
                    "\n\nकृपया उपयोगकर्ता से फसल का स्पष्ट नाम या श्रेणी पूछें।"
                    " बिना सरकारी रिकॉर्ड के भाव न बताएं।"
                )

        except Exception:
            app.logger.exception("AGMARKNET lookup failed")
            mandi_context = (
                "\n\nसरकारी मंडी डेटा प्राप्त नहीं हो पाया।"
                " कोई भाव अनुमान से न बताएं।"
            )

    prompt = question + mandi_context
    answer, status = ask_gemini(
        {"contents": [{"parts": [{"text": prompt}]}]},
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
