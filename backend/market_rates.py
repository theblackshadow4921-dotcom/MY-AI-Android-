import requests
import time
import threading
from copy import deepcopy
from functools import lru_cache
from concurrent.futures import ThreadPoolExecutor, as_completed

BASE = "https://api.agmarknet.gov.in/v1/"
SOURCE = "AGMARKNET 2.0"
MARKETS = [
    ("Bansi", 2253),
    ("Naugarh", 1727),
    ("Sahiyapur", 1740),
    ("Shohratgarh", 4822),
]
HEADERS = {"User-Agent": "Mozilla/5.0", "Accept": "application/json"}

ALIASES = {
    "आलू": "Potato", "बटाटा": "Potato", "potato": "Potato",
    "प्याज": "Onion", "कांदा": "Onion", "onion": "Onion",
    "टमाटर": "Tomato", "tomato": "Tomato",
    "बैंगन": "Brinjal", "brinjal": "Brinjal",
    "भिंडी": "Bhindi", "okra": "Bhindi", "lady finger": "Bhindi",
    "फूलगोभी": "Cauliflower", "गोभी": "Cauliflower",
    "cauliflower": "Cauliflower",
    "पत्तागोभी": "Cabbage", "बंद गोभी": "Cabbage", "cabbage": "Cabbage",
    "मटर": "Peas", "peas": "Peas",
    "हरी मिर्च": "Green Chilli", "मिर्च": "Green Chilli",
    "green chilli": "Green Chilli",
    "शिमला मिर्च": "Capsicum", "capsicum": "Capsicum",
    "लौकी": "Bottle Gourd", "bottle gourd": "Bottle Gourd",
    "करेला": "Bitter Gourd", "bitter gourd": "Bitter Gourd",
    "खीरा": "Cucumber", "ककड़ी": "Cucumber", "cucumber": "Cucumber",
    "मूली": "Radish", "radish": "Radish",
    "गाजर": "Carrot", "carrot": "Carrot",
    "पालक": "Spinach", "spinach": "Spinach",
    "लहसुन": "Garlic", "garlic": "Garlic",
    "अदरक": "Ginger", "ginger": "Ginger",
    "शकरकंद": "Sweet Potato", "sweet potato": "Sweet Potato",
    "गेहूं": "Wheat", "गेहूँ": "Wheat", "गेहू": "Wheat", "wheat": "Wheat",
    "चावल": "Rice", "धान": "Paddy(Common)", "paddy": "Paddy(Common)", "rice": "Rice",
    "चना": "Bengal Gram(Gram)(Whole)", "चने": "Bengal Gram(Gram)(Whole)",
    "काला चना": "Bengal Gram(Gram)(Whole)", "kabuli chana": "Kabuli Chana(Chickpeas-White)",
    "अरहर": "Red gram/Arhar/Tur(whole)", "तूर": "Red gram/Arhar/Tur(whole)",
    "तुअर": "Red gram/Arhar/Tur(whole)", "tur": "Red gram/Arhar/Tur(whole)",
    "मक्का": "Maize", "मकई": "Maize", "maize": "Maize",
    "बाजरा": "Pearl Millet", "ज्वार": "Jowar", "जौ": "Barley",
    "आम": "Mango", "केला": "Banana", "सेब": "Apple",
    "अंगूर": "Grapes", "अमरूद": "Guava", "पपीता": "Papaya",
    "संतरा": "Orange", "अनार": "Pomegranate",
    "अरहर": "Red gram/Arhar/Tur(whole)", "तूर": "Red gram/Arhar/Tur(whole)", "चना": "Bengal Gram(Gram)(Whole)",
    "मसूर": "Masur", "मूंग": "Moong", "मूँग": "Moong",
    "उड़द": "Urad", "उड़द दाल": "Urad",
    "हल्दी": "Turmeric", "धनिया": "Coriander",
    "कद्दू": "Pumpkin", "pumpkin": "Pumpkin",
    "परवल": "Pointed Gourd", "तुरई": "Ridge Gourd",
    "तोरी": "Ridge Gourd", "सेम": "Beans",
    "फ्रेंच बीन्स": "Beans", "beans": "Beans",
    "सहजन": "Drumstick", "drumstick": "Drumstick",
    "कच्चा केला": "Banana - Green", "अरबी": "Colacasia",
    "जिमीकंद": "Yam", "शलजम": "Turnip",
}

@lru_cache(maxsize=1)
def get_filters():
    r = requests.get(BASE + "daily-price-arrival/filters",
                     headers=HEADERS, timeout=20)
    r.raise_for_status()
    return r.json()["data"]


CATEGORY_GROUPS = {
    "cereals": 1,
    "pulses": 2,
    "fruits": 5,
    "vegetables": 6,
    "spices": 7,
}

def commodity_list(category=None):
    d = get_filters()
    groups = {1, 2, 5, 6, 7}
    if category:
        group = CATEGORY_GROUPS.get(str(category).casefold())
        if group is None:
            return []
        groups = {group}
    return [
        x for x in d.get("cmdt_data", [])
        if x.get("cmdt_group_id") in groups
    ]

def vegetable_list():
    return commodity_list("vegetables")

def normalize_commodity(value, commodities):
    if not value:
        return None

    q = str(value).strip().casefold()
    wanted = ALIASES.get(q)

    if not wanted:
        for alias in sorted(ALIASES, key=len, reverse=True):
            if alias.casefold() in q:
                wanted = ALIASES[alias]
                break

    if wanted:
        for item in commodities:
            if item["cmdt_name"].casefold() == str(wanted).casefold():
                return item

    # पूरे सवाल में सबसे लंबे आधिकारिक नाम को पहले पहचानें
    matches = [
        item for item in commodities
        if item["cmdt_name"].casefold() in q
    ]
    if matches:
        return max(matches, key=lambda x: len(x["cmdt_name"]))

    # केवल exact नाम स्वीकार करें; अस्पष्ट substring पर गलत match न करें
    for item in commodities:
        if q == item["cmdt_name"].casefold():
            return item

    return None

def read_market(market_name, market_id, commodity):
    params = {
        "marketId": market_id,
        "stateId": 34,
        "commodityId": commodity["cmdt_id"]
    }
    r = requests.get(
        BASE + "prices-and-arrivals/commodity-price/lastweek",
        params=params, headers=HEADERS, timeout=18
    )
    r.raise_for_status()
    body = r.json()
    records = []

    for row in body.get("data", []):
        unit = str(row.get("unitOfPrice", "Rs./Quintal"))
        for date, value in row.items():
            if not (
                isinstance(date, str) and len(date) == 10
                and date[:4].isdigit()
                and value not in ("NR", "", None)
            ):
                continue
            try:
                price = float(value)
            except (ValueError, TypeError):
                continue

            unit_lower = unit.casefold()
            per_quintal = None
            per_kg = None

            if "quintal" in unit_lower:
                per_quintal = round(price, 2)
                per_kg = round(price / 100, 2)
            elif "kg" in unit_lower or "kilogram" in unit_lower:
                per_kg = round(price, 2)
                per_quintal = round(price * 100, 2)
            elif "tonne" in unit_lower or "ton" in unit_lower:
                per_quintal = round(price / 10, 2)
                per_kg = round(price / 1000, 2)

            records.append({
                "state": "Uttar Pradesh",
                "district": "Siddharth Nagar",
                "market": market_name,
                "commodity": commodity["cmdt_name"],
                "variety": row.get("variety", ""),
                "arrival_date": date,
                "modal_price": price,
                "unit": unit,
                "price_per_quintal": per_quintal,
                "price_per_kg": per_kg
            })

    return records

def fetch_one_commodity(item, markets=MARKETS):
    found = []
    errors = []
    with ThreadPoolExecutor(max_workers=4) as pool:
        jobs = {
            pool.submit(read_market, name, mid, item): name
            for name, mid in markets
        }
        for job in as_completed(jobs):
            try:
                found.extend(job.result())
            except requests.RequestException as exc:
                errors.append(jobs[job] + ": " + type(exc).__name__)

    found.sort(key=lambda x: x["arrival_date"], reverse=True)
    return found, errors

def _fetch_mandi_prices_uncached(commodity=None, state=None, district=None,
                                 market=None, limit=100, offset=0, category=None):
    try:
        commodities = commodity_list(category)

        if category and not commodities:
            return {"ok": False, "error": "यह फसल श्रेणी उपलब्ध नहीं है।"}

        if commodity:
            item = normalize_commodity(commodity, commodity_list())
            if not item:
                return {
                    "ok": False,
                    "error": "यह फसल सरकारी सूची में नहीं मिली।"
                }
            selected_markets = MARKETS
            if market:
                selected_markets = [
                    x for x in MARKETS
                    if market.casefold() in x[0].casefold()
                ]
                if not selected_markets:
                    return {
                        "ok": False,
                        "error": "यह स्थानीय मंडी सूची में नहीं मिली।"
                    }
            records, errors = fetch_one_commodity(item, selected_markets)

        elif category:
            selected_markets = MARKETS
            if market:
                selected_markets = [
                    x for x in MARKETS
                    if market.casefold() in x[0].casefold()
                ]
                if not selected_markets:
                    return {
                        "ok": False,
                        "error": "यह स्थानीय मंडी सूची में नहीं मिली।"
                    }

            records = []
            errors = []
            # सीमित concurrency: बहुत अधिक एकसाथ API requests न भेजें
            with ThreadPoolExecutor(max_workers=8) as pool:
                jobs = [
                    pool.submit(fetch_one_commodity, item, selected_markets)
                    for item in commodities
                ]
                for job in as_completed(jobs):
                    try:
                        rows, failed = job.result()
                        records.extend(rows)
                        errors.extend(failed)
                    except Exception as exc:
                        errors.append(type(exc).__name__)

            records.sort(key=lambda x: x["arrival_date"], reverse=True)

        else:
            return {
                "ok": False,
                "error": "कृपया फसल का नाम या श्रेणी बताएं: vegetables, fruits, cereals, pulses या spices."
            }

        start = max(0, int(offset))
        end = start + max(1, min(int(limit), 500))
        records = records[start:end]

        return {
            "ok": True,
            "source": SOURCE,
            "district": "Siddharth Nagar",
            "markets_checked": [x[0] for x in MARKETS],
            "count": len(records),
            "records": records,
            "note": "सरकारी थोक मंडी भाव। क्विंटल और किलो के भाव केवल ज्ञात इकाइयों में गणना करके दिए गए हैं। यह खुदरा दुकान का भाव नहीं है।",
            "warnings": list(dict.fromkeys(errors))[:8]
        }

    except requests.RequestException as exc:
        return {
            "ok": False,
            "error": "AGMARKNET सर्वर से संपर्क नहीं हो पाया: " + type(exc).__name__
        }
    except (ValueError, TypeError, KeyError) as exc:
        return {
            "ok": False,
            "error": "मंडी डेटा समझने में समस्या हुई: " + type(exc).__name__
        }



# सफल AGMARKNET परिणाम 24 घंटे तक cache में रहेंगे।
_MARKET_CACHE = {}
_MARKET_CACHE_LOCK = threading.Lock()
_MARKET_CACHE_TTL = 24 * 60 * 60

def fetch_mandi_prices(commodity=None, state=None, district=None,
                       market=None, limit=100, offset=0, category=None,
                       force_refresh=False):
    key = (
        str(commodity or "").casefold(),
        str(category or "").casefold(),
        str(state or "").casefold(),
        str(district or "").casefold(),
        str(market or "").casefold(),
        int(limit),
        int(offset),
    )

    now = time.time()

    if not force_refresh:
        with _MARKET_CACHE_LOCK:
            cached = _MARKET_CACHE.get(key)
            if cached and now - cached["saved_at"] < _MARKET_CACHE_TTL:
                result = deepcopy(cached["result"])
                result["cache"] = "HIT"
                result["cache_age_seconds"] = int(now - cached["saved_at"])
                return result

    result = _fetch_mandi_prices_uncached(
        commodity=commodity,
        state=state,
        district=district,
        market=market,
        limit=limit,
        offset=offset,
        category=category,
    )

    if result.get("ok"):
        with _MARKET_CACHE_LOCK:
            _MARKET_CACHE[key] = {
                "saved_at": now,
                "result": deepcopy(result),
            }
        result["cache"] = "REFRESHED"
        result["cache_age_seconds"] = 0

    return result
