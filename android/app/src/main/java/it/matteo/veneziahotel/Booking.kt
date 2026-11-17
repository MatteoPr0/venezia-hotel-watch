package it.matteo.veneziahotel

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.text.Normalizer
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/*
 * Booking per il Lido, letto dal telefono.
 *
 * Google Hotels (la fonte del controllo su GitHub) per le date della Mostra non ha tariffe
 * per quasi nessun hotel del Lido, mentre Booking qualcosa ce l'ha. Booking però blocca le
 * richieste da server con una verifica anti-bot: per questo la ricerca la fa l'app, in una
 * WebView invisibile, come se aprissi la pagina tu dal telefono.
 */

data class BookingOffer(
    val id: String,
    val name: String,
    val total: Int,
    val link: String?,
    val image: String?,
    val score10: Double?,
    val reviews: Int?,
    val stars: Int?,
    val dist: String?,
    val zone: String = "lido",
    /** la tariffa mostrata (la più economica) ha cancellazione gratuita */
    val refundable: Boolean? = null,
    /** prezzo più basso con cancellazione gratuita, se esiste */
    val refundableTotal: Int? = null,
)

data class BookingRec(
    val offer: BookingOffer,
    val firstSeen: String,
    val lastSeen: String,
    val available: Boolean,
    val misses: Int,
    val history: List<PricePoint>,
    val badge: String?,
    val badgeAt: String?,
    val change: Int,
)

data class BookingState(
    val stay: String,
    val checkedAt: String?,
    val ok: Boolean,
    val error: String?,
    val recs: List<BookingRec>,
    val events: List<HotelEvent>,
)

/** Stesso id dello script Python, così un hotel visto da Google e da Booking coincide. */
fun hotelSlug(name: String): String {
    val ascii = Normalizer.normalize(name, Normalizer.Form.NFKD).replace(Regex("[^\\p{ASCII}]"), "").lowercase()
    val s = ascii.replace(Regex("\\b(hotel|albergo|venice|venezia|lido|di|the)\\b"), " ")
    return s.replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "x" }
}

fun stayKey(checkIn: String, checkOut: String, adults: Int) = "$checkIn|$checkOut|$adults"

fun bookingSearchUrl(checkIn: String, checkOut: String, adults: Int, freeCancel: Boolean = false, offset: Int = 0) =
    "https://www.booking.com/searchresults.it.html?ss=Lido+di+Venezia&checkin=$checkIn&checkout=$checkOut" +
        "&group_adults=$adults&no_rooms=1&group_children=0&selected_currency=EUR&lang=it" +
        (if (freeCancel) "&nflt=fc%3D2" else "") + (if (offset > 0) "&offset=$offset" else "")

private const val DESKTOP_UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

/** Legge le schede dei risultati. "wait" finché la pagina (o la verifica anti-bot) non ha finito. */
private const val EXTRACT_JS = """(function(){
var cs=document.querySelectorAll('[data-testid="property-card"]');
if(!cs.length){var t=document.body?document.body.innerText:'';
 return JSON.stringify({state:/\b0 strutture|nessuna struttura|non abbiamo trovato/i.test(t)?'empty':'wait',n:0});}
var out=[];
cs.forEach(function(c){
 function q(s){var e=c.querySelector(s);return e?e.innerText.replace(/\s+/g,' ').trim():null;}
 var a=c.querySelector('a[data-testid="title-link"]');var img=c.querySelector('img[data-testid="image"]');
 out.push({name:q('[data-testid="title"]'),price:q('[data-testid="price-and-discounted-price"]'),
  addr:q('[data-testid="address"]')||q('[data-testid="address-link"]'),dist:q('[data-testid="distance"]'),
  score:q('[data-testid="review-score"]'),
  stars:c.querySelectorAll('[data-testid="rating-stars"] span, [data-testid="rating-squares"] span').length,
  link:a?a.href.split('?')[0]:null,image:img?img.src:null,
  fc:/cancellazione gratuita/i.test(c.innerText),nonref:/non rimborsabile/i.test(c.innerText)});
});
return JSON.stringify({state:'ok',n:cs.length,items:out});
})()"""

private val LIDO_WORDS = Regex("lido|malamocco|alberoni", RegexOption.IGNORE_CASE)
/** sestieri del centro storico: Booking li mostra dopo il Lido per riempire la pagina */
private val CENTRO_WORDS = Regex("cannaregio|castello|san marco|dorsoduro|santa croce|san polo|giudecca", RegexOption.IGNORE_CASE)

private fun parsePrice(text: String?): Int? {
    if (text == null) return null
    // "€ 1.504 € 1.286": prezzo barrato e prezzo vero, vale l'ultimo
    val nums = Regex("\\d[\\d.]*").findAll(text).map { it.value.replace(".", "").toIntOrNull() }.filterNotNull().toList()
    return nums.lastOrNull()
}

private fun parseScore(text: String?): Double? =
    text?.let { Regex("(\\d{1,2}),(\\d)").find(it) }?.let { "${it.groupValues[1]}.${it.groupValues[2]}".toDoubleOrNull() }

private fun parseReviews(text: String?): Int? =
    text?.let { Regex("([\\d.]+)\\s+recension").find(it) }?.groupValues?.get(1)?.replace(".", "")?.toIntOrNull()

fun parseBookingCards(json: String): Pair<String, List<BookingOffer>> {
    val o = JSONObject(json)
    val state = o.optString("state", "wait")
    val items = o.optJSONArray("items") ?: JSONArray()
    val offers = (0 until items.length()).mapNotNull { i ->
        val c = items.getJSONObject(i)
        val name = c.optString("name").trim()
        val addr = c.optString("addr")
        val total = parsePrice(c.optString("price").ifBlank { null })
        val zone = when {
            LIDO_WORDS.containsMatchIn(addr) -> "lido"
            CENTRO_WORDS.containsMatchIn(addr) -> "centro"
            else -> return@mapNotNull null          // Murano, Mestre, terraferma…
        }
        if (name.isEmpty() || total == null) return@mapNotNull null
        val fc = c.optBoolean("fc")
        BookingOffer(
            id = hotelSlug(name), name = name, total = total,
            link = c.optString("link").ifBlank { null },
            image = c.optString("image").ifBlank { null }?.takeIf { it.startsWith("http") },
            score10 = parseScore(c.optString("score")),
            reviews = parseReviews(c.optString("score")),
            stars = c.optInt("stars", 0).takeIf { it in 1..5 },
            dist = c.optString("dist").ifBlank { null },
            zone = zone,
            refundable = if (fc) true else false,   // Booking scrive "Cancellazione gratuita" quando c'è
            refundableTotal = if (fc) total else null,
        )
    }.distinctBy { it.id }
    return state to offers
}

private suspend fun WebView.eval(js: String): String? = suspendCancellableCoroutine { c ->
    evaluateJavascript(js) { raw -> if (c.isActive) c.resume(raw) }
}

/** Una pagina di risultati Booking letta in una WebView invisibile. Restituisce (n. schede, strutture). */
@SuppressLint("SetJavaScriptEnabled")
private suspend fun loadPage(ctx: Context, url: String): Pair<Int, List<BookingOffer>> = withContext(Dispatchers.Main) {
    val wv = WebView(ctx.applicationContext)
    try {
        CookieManager.getInstance().setAcceptCookie(true)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = DESKTOP_UA
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        wv.webViewClient = WebViewClient()
        wv.measure(1366, 3000)
        wv.layout(0, 0, 1366, 3000)
        wv.loadUrl(url)
        var lastState = "wait"
        repeat(30) {                       // fino a ~45 s
            delay(1500)
            val raw = wv.eval(EXTRACT_JS) ?: return@repeat
            val str = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return@repeat
            val n = runCatching { JSONObject(str).optInt("n") }.getOrDefault(0)
            val (state, offers) = runCatching { parseBookingCards(str) }.getOrElse { return@repeat }
            lastState = state
            if (state == "ok") { delay(800); return@withContext n to offers }
            if (state == "empty") return@withContext 0 to emptyList()
        }
        error(if (lastState == "wait") "Booking non ha risposto in tempo" else "pagina Booking non riconosciuta")
    } finally {
        wv.stopLoading()
        wv.destroy()
    }
}

/** Tutte le pagine finché ci sono strutture del Lido (Booking mette prima quelle, poi riempie col resto). */
private suspend fun loadAll(ctx: Context, checkIn: String, checkOut: String, adults: Int, freeCancel: Boolean): List<BookingOffer> {
    val out = mutableListOf<BookingOffer>()
    var offset = 0
    while (offset <= 100) {
        val (n, offers) = loadPage(ctx, bookingSearchUrl(checkIn, checkOut, adults, freeCancel, offset))
        out += offers
        val lido = offers.count { it.zone == "lido" }
        if (n < 25 || lido < n / 2) break   // pagina incompleta o ormai fuori dal Lido
        offset += 25
    }
    return out.distinctBy { it.id }
}

/**
 * Strutture con prezzo al Lido (più quelle del centro storico che Booking aggiunge), con
 * rimborsabilità: una seconda ricerca col filtro "Cancellazione gratuita" dà, per chi ha anche
 * tariffe non rimborsabili, il prezzo più basso rimborsabile.
 */
suspend fun fetchBookingLido(ctx: Context, checkIn: String, checkOut: String, adults: Int): List<BookingOffer> {
    val all = loadAll(ctx, checkIn, checkOut, adults, freeCancel = false)
    val fc = runCatching { loadAll(ctx, checkIn, checkOut, adults, freeCancel = true) }.getOrNull()
    val fcById = fc?.associateBy { it.id }
    val merged = all.map { o ->
        val r = fcById?.get(o.id)
        when {
            o.refundable == true -> o
            r != null -> o.copy(refundableTotal = r.total)            // la più economica non è rimborsabile, questa sì
            fc != null -> o.copy(refundableTotal = null)              // nessuna tariffa rimborsabile
            else -> o.copy(refundable = null)                        // ricerca rimborsabili fallita: non sappiamo
        }
    }
    // strutture che compaiono solo nella ricerca "rimborsabili" (capita): aggiunte anche loro
    val extra = fc.orEmpty().filter { r -> merged.none { it.id == r.id } }
    return merged + extra
}

/* ── Memoria locale dei risultati Booking: storico prezzi, novità, esauriti ── */

class BookingStore(ctx: Context) {
    private val file = File(ctx.filesDir, "booking.json")

    fun load(): BookingState? = runCatching { if (file.exists()) parseState(JSONObject(file.readText())) else null }.getOrNull()

    /** Minuti dall'ultimo controllo andato a buon fine per questo soggiorno (null = mai). */
    fun minutesSinceCheck(stay: String): Long? = load()?.takeIf { it.stay == stay && it.ok }?.checkedAt
        ?.let { runCatching { ChronoUnit.MINUTES.between(Instant.parse(it), Instant.now()) }.getOrNull() }

    fun saveError(stay: String, msg: String): BookingState {
        val old = load()?.takeIf { it.stay == stay }
        val st = (old ?: BookingState(stay, null, false, null, emptyList(), emptyList()))
            .copy(ok = false, error = msg, checkedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
        file.writeText(toJson(st).toString())
        return st
    }

    fun update(stay: String, offers: List<BookingOffer>, dropMin: Int = 25): BookingState {
        val ts = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        val today = ts.take(10)
        val dayAgo = Instant.now().minus(24, ChronoUnit.HOURS).toString()
        val old = load()?.takeIf { it.stay == stay }
        val prev = old?.recs?.associateBy { it.offer.id }.orEmpty().toMutableMap()
        val events = mutableListOf<HotelEvent>()
        fun ev(type: String, o: BookingOffer, prevTotal: Int?) =
            events.add(HotelEvent(ts, type, o.id, o.name, o.zone, o.total, prevTotal))

        val out = mutableListOf<BookingRec>()
        for (o in offers) {
            val p = prev.remove(o.id)
            val hist = p?.history.orEmpty().toMutableList()
            if (hist.lastOrNull()?.day == today) hist[hist.lastIndex] = PricePoint(today, o.total) else hist.add(PricePoint(today, o.total))
            var badge: String? = null; var badgeAt: String? = null; var change = 0
            when {
                p == null -> { badge = "new"; badgeAt = ts; ev("new", o, null) }
                !p.available -> { badge = "back"; badgeAt = ts; ev("back", o, p.offer.total) }
                p.offer.total != o.total -> {
                    change = o.total - p.offer.total
                    badge = if (change < 0) "drop" else "up"; badgeAt = ts
                    if (-change >= dropMin) ev("drop", o, p.offer.total)
                }
                p.badge != null && p.badge != "gone" && (p.badgeAt ?: "") >= dayAgo -> {
                    badge = p.badge; badgeAt = p.badgeAt; change = p.change
                }
            }
            out.add(BookingRec(o, p?.firstSeen ?: ts, ts, true, 0, hist.takeLast(120), badge, badgeAt, change))
        }
        // spariti da Booking: esauriti dopo 2 controlli consecutivi senza di loro
        for (p in prev.values) {
            val misses = p.misses + 1
            if (p.available && misses >= 2) {
                out.add(p.copy(available = false, misses = misses, badge = "gone", badgeAt = ts))
                events.add(HotelEvent(ts, "gone", p.offer.id, p.offer.name, p.offer.zone, p.offer.total, p.offer.total))
            } else out.add(p.copy(misses = misses))
        }
        val st = BookingState(stay, ts, true, null, out, (events + old?.events.orEmpty()).take(60))
        file.writeText(toJson(st).toString())
        return st
    }

    private fun parseState(o: JSONObject): BookingState {
        val recs = o.optJSONArray("recs") ?: JSONArray()
        val evs = o.optJSONArray("events") ?: JSONArray()
        return BookingState(
            stay = o.optString("stay"),
            checkedAt = o.optString("checked_at").ifBlank { null },
            ok = o.optBoolean("ok"),
            error = o.optString("error").ifBlank { null },
            recs = (0 until recs.length()).map { i ->
                val r = recs.getJSONObject(i)
                val h = r.optJSONArray("history") ?: JSONArray()
                BookingRec(
                    offer = BookingOffer(
                        r.getString("id"), r.getString("name"), r.getInt("total"),
                        r.optString("link").ifBlank { null }, r.optString("image").ifBlank { null },
                        if (r.has("score10")) r.optDouble("score10") else null,
                        if (r.has("reviews")) r.optInt("reviews") else null,
                        if (r.has("stars")) r.optInt("stars") else null,
                        r.optString("dist").ifBlank { null },
                        zone = r.optString("zone", "lido"),
                        refundable = if (r.has("refundable")) r.optBoolean("refundable") else null,
                        refundableTotal = if (r.has("refundable_total")) r.optInt("refundable_total") else null,
                    ),
                    firstSeen = r.optString("first_seen"), lastSeen = r.optString("last_seen"),
                    available = r.optBoolean("available", true), misses = r.optInt("misses"),
                    history = (0 until h.length()).map { h.getJSONObject(it).let { p -> PricePoint(p.getString("d"), p.getInt("total")) } },
                    badge = r.optString("badge").ifBlank { null }, badgeAt = r.optString("badge_at").ifBlank { null },
                    change = r.optInt("change"),
                )
            },
            events = (0 until evs.length()).map { i ->
                val e = evs.getJSONObject(i)
                HotelEvent(e.getString("t"), e.getString("type"), e.getString("id"), e.getString("name"), e.optString("zone", "lido"),
                    if (e.has("total")) e.optInt("total") else null, if (e.has("prev_total")) e.optInt("prev_total") else null)
            },
        )
    }

    private fun toJson(s: BookingState) = JSONObject().apply {
        put("stay", s.stay); put("checked_at", s.checkedAt); put("ok", s.ok); put("error", s.error)
        put("recs", JSONArray().apply {
            s.recs.forEach { r ->
                put(JSONObject().apply {
                    val o = r.offer
                    put("id", o.id); put("name", o.name); put("total", o.total); put("link", o.link); put("image", o.image)
                    o.score10?.let { put("score10", it) }; o.reviews?.let { put("reviews", it) }; o.stars?.let { put("stars", it) }
                    put("dist", o.dist); put("zone", o.zone)
                    o.refundable?.let { put("refundable", it) }; o.refundableTotal?.let { put("refundable_total", it) }
                    put("first_seen", r.firstSeen); put("last_seen", r.lastSeen); put("available", r.available)
                    put("misses", r.misses); put("badge", r.badge); put("badge_at", r.badgeAt); put("change", r.change)
                    put("history", JSONArray().apply { r.history.forEach { put(JSONObject().put("d", it.day).put("total", it.total)) } })
                })
            }
        })
        put("events", JSONArray().apply {
            s.events.forEach { e ->
                put(JSONObject().apply {
                    put("t", e.time); put("type", e.type); put("id", e.id); put("name", e.name); put("zone", e.zone)
                    e.total?.let { put("total", it) }; e.prevTotal?.let { put("prev_total", it) }
                })
            }
        })
    }
}

/**
 * Unisce i risultati Booking alla fotografia di Google. Se Google ha già un prezzo valido
 * per lo stesso hotel resta quello; altrimenti vale Booking (coordinate prese da Google se c'è).
 */
fun mergeBooking(s: Snapshot, st: BookingState?): Snapshot {
    if (st == null || st.stay != stayKey(s.checkIn, s.checkOut, s.adults) || st.recs.isEmpty()) return s
    val hotels = s.hotels.toMutableList()
    for (r in st.recs) {
        val o = r.offer
        val idx = hotels.indexOfFirst { it.id == o.id }
        val g = hotels.getOrNull(idx)
        if (g != null && g.available && g.total != null) {
            // Google ha già un prezzo: resta quello, ma se Google non sa se è rimborsabile lo dice Booking
            if (g.refundable == null && o.refundable != null)
                hotels[idx] = g.copy(refundable = o.refundable, refundableTotal = o.refundableTotal, refundSource = "booking")
            continue
        }
        if (g == null && !r.available) continue
        // del centro storico ci interessano solo quelli entro budget (Google li copre già bene)
        if (o.zone == "centro" && o.total > s.budgetMax * 1.25) continue
        val link = o.link?.let { "$it?checkin=${s.checkIn}&checkout=${s.checkOut}&group_adults=${s.adults}&no_rooms=1&group_children=0&selected_currency=EUR&lang=it" }
        val h = Hotel(
            id = o.id, name = g?.name ?: o.name, zone = o.zone,
            stars = o.stars ?: g?.stars,
            rating = o.score10?.let { (it / 2 * 10).roundToInt() / 10.0 } ?: g?.rating,
            reviews = o.reviews ?: g?.reviews,
            total = o.total, perNight = (o.total.toDouble() / s.nights).roundToInt(),
            link = link, image = o.image ?: g?.image, lat = g?.lat, lon = g?.lon,
            available = r.available, badge = r.badge, change = r.change,
            minTotal = r.history.minOfOrNull { it.total }, firstSeen = r.firstSeen, lastSeen = r.lastSeen,
            amenities = listOfNotNull(o.dist?.takeIf { o.zone == "lido" }?.replace("dal centro", "dal centro del Lido")) + (g?.amenities ?: emptyList()),
            history = r.history, source = "booking",
            refundable = o.refundable, refundableTotal = o.refundableTotal, refundSource = "booking",
        )
        if (g != null) hotels[idx] = h else hotels.add(h)
    }
    val events = (s.events + st.events).sortedByDescending { it.time }
    return s.copy(hotels = hotels, events = events)
}

/** Esegue il controllo Booking (se serve) e salva il risultato. Non lancia eccezioni. */
suspend fun checkBooking(ctx: Context, s: Snapshot, force: Boolean, minMinutes: Long = 30): BookingState? {
    val store = BookingStore(ctx)
    val stay = stayKey(s.checkIn, s.checkOut, s.adults)
    val age = store.minutesSinceCheck(stay)
    if (!force && age != null && age < minMinutes) return store.load()
    return runCatching { fetchBookingLido(ctx, s.checkIn, s.checkOut, s.adults) }
        .map { store.update(stay, it) }
        .getOrElse { store.saveError(stay, it.message ?: "errore") }
}
