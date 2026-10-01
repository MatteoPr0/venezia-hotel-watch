package it.matteo.veneziahotel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/* ── Selezione curata degli hotel migliori per la Mostra (data/curated.json nel repo) ── */

data class CuratedHotel(
    val name: String,
    val match: String,
    val stars: Int?,
    val rating: Double?,
    val reviews: Int?,
    val lat: Double,
    val lon: Double,
    val site: String?,
    val email: String?,
    val phone: String?,
    val address: String?,
    val fit: String,
    val note: String,
    val walkVaporettoMin: Int?,
)

data class CuratedGroup(val title: String, val subtitle: String, val hotels: List<CuratedHotel>)
data class Curated(val updated: String, val intro: String, val groups: List<CuratedGroup>)

private fun JSONObject.s(k: String): String? = if (has(k) && !isNull(k)) optString(k).ifBlank { null } else null
private fun JSONObject.i(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
private fun JSONObject.d(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k) else null

fun parseCurated(text: String): Curated {
    val o = JSONObject(text)
    val groups = o.getJSONArray("groups").let { ga ->
        (0 until ga.length()).map { gi ->
            val g = ga.getJSONObject(gi)
            val ha = g.getJSONArray("hotels")
            CuratedGroup(
                g.optString("title"), g.optString("subtitle"),
                (0 until ha.length()).map { hi ->
                    val h = ha.getJSONObject(hi)
                    CuratedHotel(
                        name = h.optString("name"), match = h.optString("match"),
                        stars = h.i("stars"), rating = h.d("rating"), reviews = h.i("reviews"),
                        lat = h.optDouble("lat"), lon = h.optDouble("lon"),
                        site = h.s("site"), email = h.s("email"), phone = h.s("phone"), address = h.s("address"),
                        fit = h.optString("fit", "budget"), note = h.optString("note"),
                        walkVaporettoMin = h.i("walk_vaporetto_min"),
                    )
                }
            )
        }
    }
    return Curated(o.optString("updated"), o.optString("intro"), groups)
}

/* ── Distanze dal Palazzo del Cinema ── */

const val PALAZZO_LAT = 45.4047
const val PALAZZO_LON = 12.3681

fun distPalazzoM(lat: Double?, lon: Double?): Int? {
    if (lat == null || lon == null) return null
    val r = 6371000.0
    val la1 = Math.toRadians(PALAZZO_LAT); val la2 = Math.toRadians(lat)
    val dx = (Math.toRadians(lon) - Math.toRadians(PALAZZO_LON)) * cos((la1 + la2) / 2)
    return (r * hypot(dx, la2 - la1)).roundToInt()
}

/** minuti a piedi stimati (percorso ~25% più lungo della linea d'aria, 75 m/min) */
fun walkMin(m: Int) = maxOf(1, (m * 1.25 / 75).roundToInt())

fun palazzoLabel(lat: Double?, lon: Double?): String? {
    val m = distPalazzoM(lat, lon) ?: return null
    if (m > 3500) return null
    val dist = if (m < 1000) "$m m" else String.format(java.util.Locale.ITALIAN, "%.1f km", m / 1000.0)
    return "$dist dal Palazzo · ${walkMin(m)} min a piedi"
}

/* ── Repo: scaricamento con cache + copia inclusa nell'app ── */

fun Repo.curatedUrl(): String = dataUrl.substringBeforeLast('/') + "/curated.json"

fun Repo.cachedCurated(ctx: Context): Curated? = runCatching {
    val f = File(ctx.filesDir, "curated.json")
    val text = if (f.exists()) f.readText() else ctx.assets.open("curated.json").bufferedReader().use { it.readText() }
    parseCurated(text)
}.getOrNull()

suspend fun Repo.fetchCurated(ctx: Context): Curated = withContext(Dispatchers.IO) {
    val url = URL(curatedUrl() + "?t=" + System.currentTimeMillis() / 60000)
    val conn = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 20000 }
    try {
        if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val c = parseCurated(text)
        File(ctx.filesDir, "curated.json").writeText(text)
        c
    } finally { conn.disconnect() }
}

/* ── "Controllato il…" per ogni hotel della selezione ── */

fun checkedOn(ctx: Context, match: String): String? =
    ctx.getSharedPreferences("checked", Context.MODE_PRIVATE).getString(match, null)

fun markChecked(ctx: Context, match: String) =
    ctx.getSharedPreferences("checked", Context.MODE_PRIVATE).edit().putString(match, LocalDate.now().toString()).apply()
