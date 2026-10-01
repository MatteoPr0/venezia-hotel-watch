#!/usr/bin/env python3
"""Controlla gli hotel disponibili per il soggiorno alla Mostra di Venezia
via SerpApi (Google Hotels) e aggiorna data/hotels.json.

Variabili d'ambiente:
  SERPAPI_KEY     chiave SerpApi (obbligatoria, salvo SERPAPI_MOCK_DIR)
  NTFY_TOPIC      opzionale: topic ntfy.sh per push di riserva
  SERPAPI_MOCK_DIR opzionale: cartella con risposte JSON finte (test)
"""
from __future__ import annotations

import datetime as dt
import json
import math
import os
import re
import sys
import unicodedata
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / "config.json"
DATA = ROOT / "data" / "hotels.json"
SERPAPI = "https://serpapi.com/search.json"
MAX_EVENTS = 150
MAX_HISTORY = 120


def now_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def slug(name: str) -> str:
    s = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode().lower()
    s = re.sub(r"\b(hotel|albergo|venice|venezia|lido|di|the)\b", " ", s)
    return re.sub(r"[^a-z0-9]+", "-", s).strip("-") or "x"


def classify_zone(lat: float | None, lon: float | None) -> str | None:
    """Zona dalle coordinate: 'lido', 'centro' (centro storico + Giudecca) o None."""
    if lat is None or lon is None:
        return None
    if 45.425 <= lat <= 45.450 and 12.300 <= lon <= 12.372:
        return "centro"
    if 45.33 <= lat < 45.40 and lon >= 12.32:
        return "lido"
    if 45.40 <= lat <= 45.437 and lon >= 12.35:
        return "lido"
    return None


def http_json(url: str, timeout: int = 60) -> dict:
    req = urllib.request.Request(url, headers={"User-Agent": "venezia-hotel-watch/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


class Fetcher:
    def __init__(self, key: str | None, mock_dir: str | None):
        self.key = key
        self.mock_dir = Path(mock_dir) if mock_dir else None
        self.calls = 0

    def search(self, params: dict) -> dict:
        self.calls += 1
        if self.mock_dir:
            f = self.mock_dir / f"{params.get('_mock')}.json"
            return json.loads(f.read_text()) if f.exists() else {"properties": []}
        p = {k: v for k, v in params.items() if not k.startswith("_")}
        p["api_key"] = self.key
        return http_json(SERPAPI + "?" + urllib.parse.urlencode(p))

    def searches_left(self) -> int | None:
        if self.mock_dir or not self.key:
            return None
        try:
            acc = http_json("https://serpapi.com/account.json?" + urllib.parse.urlencode({"api_key": self.key}), 20)
            return acc.get("total_searches_left", acc.get("plan_searches_left"))
        except Exception:
            return None


def num(v):
    try:
        return None if v is None else float(v)
    except (TypeError, ValueError):
        return None


def parse_property(p: dict, nights: int) -> dict | None:
    if p.get("type") and p.get("type") != "hotel":
        return None
    name = (p.get("name") or "").strip()
    if not name:
        return None
    gps = p.get("gps_coordinates") or {}
    lat, lon = num(gps.get("latitude")), num(gps.get("longitude"))
    total = num((p.get("total_rate") or {}).get("extracted_lowest"))
    night = num((p.get("rate_per_night") or {}).get("extracted_lowest"))
    if total is None and night is not None:
        total = night * nights
    if night is None and total is not None:
        night = total / nights
    imgs = p.get("images") or []
    image = None
    if imgs:
        image = imgs[0].get("original_image") or imgs[0].get("thumbnail")
    return {
        "id": slug(name),
        "token": p.get("property_token"),
        "name": name,
        "lat": lat,
        "lon": lon,
        "stars": p.get("extracted_hotel_class"),
        "rating": num(p.get("overall_rating")),
        "reviews": p.get("reviews"),
        "total": round(total) if total is not None else None,
        "per_night": round(night) if night is not None else None,
        "link": p.get("link"),
        "image": image,
        "amenities": (p.get("amenities") or [])[:8],
    }


def run_searches(cfg: dict, fetcher: Fetcher) -> tuple[dict, list[str]]:
    ci, co = cfg["check_in"], cfg["check_out"]
    nights = (dt.date.fromisoformat(co) - dt.date.fromisoformat(ci)).days
    cap = cfg["budget_max"] * cfg.get("show_up_to_factor", 1.25)
    found: dict[str, dict] = {}
    errors: list[str] = []
    for s in cfg["searches"]:
        params = {
            "engine": "google_hotels",
            "q": s["q"],
            "check_in_date": ci,
            "check_out_date": co,
            "adults": cfg.get("adults", 2),
            "currency": cfg.get("currency", "EUR"),
            "gl": "it",
            "hl": "it",
        }
        params.update(s.get("params", {}))
        if s.get("use_max_price"):
            params["max_price"] = str(math.ceil(cap / nights))
        token = None
        for page in range(s.get("pages", 1)):
            if token:
                params["next_page_token"] = token
            params["_mock"] = f"{s['zone']}_{page}"
            try:
                res = fetcher.search(params)
            except Exception as e:  # rete / quota
                errors.append(f"{s['zone']} p{page + 1}: {e}")
                break
            if res.get("error"):
                errors.append(f"{s['zone']} p{page + 1}: {res['error']}")
                break
            for p in res.get("properties", []):
                h = parse_property(p, nights)
                if not h:
                    continue
                zone = classify_zone(h["lat"], h["lon"])
                if zone not in ("lido", "centro"):
                    continue  # Mestre, terraferma, isole minori
                if h["total"] is not None and h["total"] > cap:
                    continue
                h["zone"] = zone
                prev = found.get(h["id"])
                if prev is None or (h["total"] or 1e9) < (prev["total"] or 1e9):
                    found[h["id"]] = h
            token = (res.get("serpapi_pagination") or {}).get("next_page_token")
            if not token:
                break
    return found, errors


def merge(old: dict, found: dict, cfg: dict, ts: str, run_ok: bool) -> tuple[list, list]:
    today = ts[:10]
    day_ago = (dt.datetime.fromisoformat(ts.replace("Z", "+00:00")) - dt.timedelta(hours=24)) \
        .isoformat().replace("+00:00", "Z")
    old_hotels = {h["id"]: h for h in old.get("hotels", [])}
    events: list[dict] = []
    out: list[dict] = []
    drop_min = cfg.get("drop_alert_min_eur", 25)

    def ev(kind, h, prev=None):
        events.append({"t": ts, "type": kind, "id": h["id"], "name": h["name"], "zone": h["zone"],
                       "total": h.get("total"), "prev_total": prev})

    for hid, h in found.items():
        o = old_hotels.pop(hid, None)
        hist = list(o.get("history", [])) if o else []
        if h["total"] is not None:
            if hist and hist[-1]["d"] == today:
                hist[-1]["total"] = h["total"]
            else:
                hist.append({"d": today, "total": h["total"]})
        hist = hist[-MAX_HISTORY:]
        rec = {**h,
               "first_seen": o["first_seen"] if o else ts,
               "last_seen": ts,
               "available": True,
               "misses": 0,
               "history": hist,
               "min_total": min([x["total"] for x in hist], default=None),
               "change": 0}
        prev_total = o.get("total") if o else None
        rec["badge"], rec["badge_at"] = None, None
        if o is None:
            rec["badge"], rec["badge_at"] = "new", ts
            ev("new", rec)
        elif not o.get("available", True):
            rec["badge"], rec["badge_at"] = "back", ts
            ev("back", rec, prev_total)
        elif prev_total is not None and rec["total"] is not None and rec["total"] != prev_total:
            rec["change"] = rec["total"] - prev_total
            rec["badge"], rec["badge_at"] = ("drop" if rec["change"] < 0 else "up"), ts
            if -rec["change"] >= drop_min:
                ev("drop", rec, prev_total)
        elif o.get("badge") in ("new", "drop", "up", "back") and (o.get("badge_at") or "") >= day_ago:
            # il badge resta visibile per 24h
            rec["badge"], rec["badge_at"], rec["change"] = o["badge"], o["badge_at"], o.get("change", 0)
        out.append(rec)

    # hotel non più presenti: se la ricerca è andata bene contiamo i "mancati";
    # dopo 2 controlli consecutivi li segniamo non disponibili.
    for o in old_hotels.values():
        o = dict(o)
        if run_ok:
            o["misses"] = o.get("misses", 0) + 1
            if o.get("available", True) and o["misses"] >= 2:
                o["available"] = False
                o["badge"] = "gone"
                ev("gone", o, o.get("total"))
        out.append(o)

    out.sort(key=lambda h: (not h.get("available", True), h.get("total") is None, h.get("total") or 0))
    return out, events


def notify_ntfy(topic: str, events: list[dict], cfg: dict):
    good = [e for e in events if e["type"] in ("new", "back", "drop")
            and (e["total"] is None or e["total"] <= cfg["budget_max"])]
    if not good:
        return
    lines = []
    for e in good[:8]:
        price = f"{e['total']} €" if e["total"] is not None else "prezzo n.d."
        tag = {"new": "Nuovo", "back": "Di nuovo libero", "drop": "Prezzo giù"}[e["type"]]
        extra = f" (era {e['prev_total']} €)" if e["type"] == "drop" and e["prev_total"] else ""
        lines.append(f"{tag}: {e['name']} · {e['zone'].capitalize()} · {price}{extra}")
    req = urllib.request.Request(
        f"https://ntfy.sh/{topic}", data="\n".join(lines).encode(),
        headers={"Title": f"Venezia 2027: {len(good)} novità hotel", "Tags": "hotel,clapper"})
    try:
        urllib.request.urlopen(req, timeout=20)
    except Exception as e:
        print("ntfy fallito:", e, file=sys.stderr)


def main() -> int:
    cfg = json.loads(CONFIG.read_text())
    key = os.environ.get("SERPAPI_KEY")
    mock = os.environ.get("SERPAPI_MOCK_DIR")
    if not key and not mock:
        print("SERPAPI_KEY mancante: aggiungila nei Secrets del repository.", file=sys.stderr)
        return 1
    old = json.loads(DATA.read_text()) if DATA.exists() else {}
    ts = now_iso()
    fetcher = Fetcher(key, mock)
    found, errors = run_searches(cfg, fetcher)
    run_ok = not errors
    hotels, events = merge(old, found, cfg, ts, run_ok)
    nights = (dt.date.fromisoformat(cfg["check_out"]) - dt.date.fromisoformat(cfg["check_in"])).days
    data = {
        "updated_at": ts,
        "trip_name": cfg.get("trip_name"),
        "stay": {"check_in": cfg["check_in"], "check_out": cfg["check_out"], "nights": nights,
                 "adults": cfg.get("adults", 2)},
        "budget": {"target": cfg["budget_target"], "max": cfg["budget_max"], "currency": cfg.get("currency", "EUR")},
        "last_run": {"ok": run_ok, "errors": errors, "api_calls": fetcher.calls,
                     "found": len(found), "searches_left": fetcher.searches_left()},
        "hotels": hotels,
        "events": (events + old.get("events", []))[:MAX_EVENTS],
    }
    DATA.parent.mkdir(parents=True, exist_ok=True)
    DATA.write_text(json.dumps(data, ensure_ascii=False, indent=1))
    print(f"OK: {len(found)} hotel trovati, {len(events)} eventi, {fetcher.calls} chiamate. Errori: {errors or 'nessuno'}")
    if os.environ.get("NTFY_TOPIC"):
        notify_ntfy(os.environ["NTFY_TOPIC"], events, cfg)
    return 0


if __name__ == "__main__":
    sys.exit(main())
