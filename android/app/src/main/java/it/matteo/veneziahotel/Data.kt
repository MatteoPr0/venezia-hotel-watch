package it.matteo.veneziahotel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class PricePoint(val day: String, val total: Int)

data class Hotel(
    val id: String,
    val name: String,
    val zone: String,
    val stars: Int?,
    val rating: Double?,
    val reviews: Int?,
    val total: Int?,
    val perNight: Int?,
    val link: String?,
    val image: String?,
    val lat: Double?,
    val lon: Double?,
    val available: Boolean,
    val badge: String?,
    val change: Int,
    val minTotal: Int?,
    val firstSeen: String,
    val lastSeen: String,
    val amenities: List<String>,
    val history: List<PricePoint>,
)

data class HotelEvent(
    val time: String,
    val type: String,
    val id: String,
    val name: String,
    val zone: String,
    val total: Int?,
    val prevTotal: Int?,
)

data class Snapshot(
    val updatedAt: String?,
    val tripName: String,
    val checkIn: String,
    val checkOut: String,
    val nights: Int,
    val adults: Int,
    val budgetTarget: Int,
    val budgetMax: Int,
    val runOk: Boolean,
    val runErrors: List<String>,
    val searchesLeft: Int?,
    val hotels: List<Hotel>,
    val events: List<HotelEvent>,
)

private fun JSONObject.optIntOrNull(k: String): Int? =
    if (has(k) && !isNull(k)) optDouble(k).let { if (it.isNaN()) null else it.toInt() } else null

private fun JSONObject.optDoubleOrNull(k: String): Double? =
    if (has(k) && !isNull(k)) optDouble(k).let { if (it.isNaN()) null else it } else null

private fun JSONObject.optStr(k: String): String? =
    if (has(k) && !isNull(k)) optString(k).ifBlank { null } else null

private inline fun <T> JSONArray?.mapObj(f: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).map { f(getJSONObject(it)) }

fun parseSnapshot(text: String): Snapshot {
    val o = JSONObject(text)
    val stay = o.optJSONObject("stay") ?: JSONObject()
    val budget = o.optJSONObject("budget") ?: JSONObject()
    val run = o.optJSONObject("last_run") ?: JSONObject()
    val hotels = o.optJSONArray("hotels").mapObj { h ->
        Hotel(
            id = h.optString("id"),
            name = h.optString("name"),
            zone = h.optString("zone", "lido"),
            stars = h.optIntOrNull("stars"),
            rating = h.optDoubleOrNull("rating"),
            reviews = h.optIntOrNull("reviews"),
            total = h.optIntOrNull("total"),
            perNight = h.optIntOrNull("per_night"),
            link = h.optStr("link"),
            image = h.optStr("image"),
            lat = h.optDoubleOrNull("lat"),
            lon = h.optDoubleOrNull("lon"),
            available = h.optBoolean("available", true),
            badge = h.optStr("badge"),
            change = h.optIntOrNull("change") ?: 0,
            minTotal = h.optIntOrNull("min_total"),
            firstSeen = h.optString("first_seen"),
            lastSeen = h.optString("last_seen"),
            amenities = h.optJSONArray("amenities")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            history = h.optJSONArray("history").mapObj { p -> PricePoint(p.optString("d"), p.optInt("total")) },
        )
    }
    val events = o.optJSONArray("events").mapObj { e ->
        HotelEvent(
            time = e.optString("t"),
            type = e.optString("type"),
            id = e.optString("id"),
            name = e.optString("name"),
            zone = e.optString("zone"),
            total = e.optIntOrNull("total"),
            prevTotal = e.optIntOrNull("prev_total"),
        )
    }
    return Snapshot(
        updatedAt = o.optStr("updated_at"),
        tripName = o.optStr("trip_name") ?: "Venezia 2027",
        checkIn = stay.optString("check_in", "2027-09-03"),
        checkOut = stay.optString("check_out", "2027-09-06"),
        nights = stay.optInt("nights", 3),
        adults = stay.optInt("adults", 2),
        budgetTarget = budget.optInt("target", 1000),
        budgetMax = budget.optInt("max", 1200),
        runOk = run.optBoolean("ok", true),
        runErrors = run.optJSONArray("errors")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
        searchesLeft = run.optIntOrNull("searches_left"),
        hotels = hotels,
        events = events,
    )
}

class Repo(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val cache = File(ctx.filesDir, "hotels.json")

    var dataUrl: String
        get() = prefs.getString("data_url", null) ?: BuildConfig.DATA_URL
        set(v) = prefs.edit().putString("data_url", v.trim()).apply()

    var notificationsOn: Boolean
        get() = prefs.getBoolean("notify", true)
        set(v) = prefs.edit().putBoolean("notify", v).apply()

    /** Notifica solo hotel entro questo totale (default: budget massimo). */
    var notifyOnlyWithinMax: Boolean
        get() = prefs.getBoolean("notify_within_max", true)
        set(v) = prefs.edit().putBoolean("notify_within_max", v).apply()

    var lastNotifiedEvent: String
        get() = prefs.getString("last_event", "") ?: ""
        set(v) = prefs.edit().putString("last_event", v).apply()

    fun cached(): Snapshot? = runCatching { if (cache.exists()) parseSnapshot(cache.readText()) else null }.getOrNull()

    suspend fun fetch(): Snapshot = withContext(Dispatchers.IO) {
        // parametro anti-cache: raw.githubusercontent.com tiene una cache di ~5 minuti
        val url = URL(dataUrl + (if ('?' in dataUrl) "&" else "?") + "t=" + System.currentTimeMillis() / 60000)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val snap = parseSnapshot(text)
            cache.writeText(text)
            snap
        } finally {
            conn.disconnect()
        }
    }
}
