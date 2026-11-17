package it.matteo.veneziahotel

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.Html
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Avvisi sulla Mostra del Cinema: legge il feed RSS della Biennale e segnala gli annunci
 * dell'edizione del soggiorno (date, film in selezione, programma per il pubblico, biglietteria).
 * Quando un annuncio dice l'ora di apertura della vendita online ("a partire dalle ore 15 di
 * martedì 11 agosto", com'era nel 2026) mette due sveglie: 30 minuti prima e all'apertura.
 */

private const val RSS = "https://www.labiennale.org/it/rss.xml"
const val TICKETS_URL = "https://biennalecinema.vivaticket.it/"
private val ROME: ZoneId = ZoneId.of("Europe/Rome")
private val MONTHS = mapOf("luglio" to 7, "agosto" to 8, "settembre" to 9)

data class FestivalNews(val id: String, val kind: String, val title: String, val link: String, val date: String)

data class FestivalState(
    val year: Int,
    /** annunci della Mostra di quell'anno, dal più recente */
    val news: List<FestivalNews>,
    /** apertura della vendita online (ms), se annunciata */
    val saleAt: Long?,
    val festStart: String?,
    val festEnd: String?,
    val checkedAt: String?,
    val ok: Boolean,
    val notified: Set<String>,
    /** annunci già letti per intero (non si riscaricano) */
    val read: Set<String>,
)

fun festivalYear(s: Snapshot?): Int = s?.checkIn?.take(4)?.toIntOrNull() ?: 2027

/** Etichetta breve dell'apertura vendita: "mar 10 ago, ore 15". */
fun saleLabel(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ROME)
    val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ITALIAN).format(t)
    val hour = if (t.minute == 0) "${t.hour}" else String.format(Locale.ITALIAN, "%d:%02d", t.hour, t.minute)
    return "$day, ore $hour"
}

object FestivalWatch {
    private const val FILE = "festival.json"
    const val CHANNEL = "festival"

    fun load(ctx: Context): FestivalState? = runCatching {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        val n = o.optJSONArray("news") ?: JSONArray()
        FestivalState(
            year = o.optInt("year"),
            news = (0 until n.length()).map { i ->
                n.getJSONObject(i).let { FestivalNews(it.getString("id"), it.getString("kind"), it.getString("title"), it.getString("link"), it.optString("date")) }
            },
            saleAt = if (o.has("sale_at")) o.optLong("sale_at") else null,
            festStart = o.optString("fest_start").ifBlank { null },
            festEnd = o.optString("fest_end").ifBlank { null },
            checkedAt = o.optString("checked_at").ifBlank { null },
            ok = o.optBoolean("ok", true),
            notified = o.optJSONArray("notified").strings().toSet(),
            read = o.optJSONArray("read").strings().toSet(),
        )
    }.getOrNull()

    private fun JSONArray?.strings() = if (this == null) emptyList() else (0 until length()).map { getString(it) }

    private fun save(ctx: Context, s: FestivalState) {
        val o = JSONObject().apply {
            put("year", s.year)
            put("news", JSONArray().apply {
                s.news.forEach { put(JSONObject().put("id", it.id).put("kind", it.kind).put("title", it.title).put("link", it.link).put("date", it.date)) }
            })
            s.saleAt?.let { put("sale_at", it) }
            put("fest_start", s.festStart); put("fest_end", s.festEnd)
            put("checked_at", s.checkedAt); put("ok", s.ok)
            put("notified", JSONArray(s.notified.toList())); put("read", JSONArray(s.read.toList()))
        }
        File(ctx.filesDir, FILE).writeText(o.toString())
    }

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 20000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) VeneziaHotel")
        }
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun tag(item: String, name: String): String =
        Regex("<$name[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(item)?.groupValues?.get(1)
            ?.replace("<![CDATA[", "")?.replace("]]>", "")?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }
            ?.replace(Regex("\\s+"), " ")?.trim() ?: ""

    private fun norm(t: String) = t.lowercase(Locale.ITALIAN).replace('’', '\'')

    /** L'annuncio riguarda la Mostra di quell'anno? */
    fun isEdition(text: String, year: Int): Boolean {
        val t = norm(text)
        val ed = year - 1943                      // 2026 → 83. Mostra, 2027 → 84.
        return Regex("\\b$ed\\s*[.°ª]?\\s*mostra").containsMatchIn(t) ||
            "biennale cinema $year" in t ||
            ("cinema" in t && "mostra" in t && "$year" in t)
    }

    fun kindOf(text: String): String {
        val t = norm(text)
        return when {
            "biglietteria" in t || ("bigliett" in t && ("vendita" in t || "acquist" in t || "online" in t)) -> "biglietti"
            "programma" in t && ("pubblico" in t || "proiezioni" in t) -> "programma"
            "selezione ufficiale" in t || "in concorso" in t || "line-up" in t || "lineup" in t -> "selezione"
            "abbonament" in t -> "abbonamenti"
            Regex("\\d{1,2}\\s*(?:al|-|–)\\s*\\d{1,2}\\s+settembre").containsMatchIn(t) -> "date"
            else -> "altro"
        }
    }

    private fun at(year: Int, month: String, day: String, hour: String, min: String?): Long? = runCatching {
        LocalDateTime.of(year, MONTHS.getValue(month), day.toInt(), hour.toInt(), min?.toIntOrNull() ?: 0)
            .atZone(ROME).toInstant().toEpochMilli()
    }.getOrNull()

    /** Orari di apertura della vendita citati nel testo (vicino a "vendita"/"biglietti"/"disponibilità"). */
    fun saleTimes(text: String, year: Int): List<Long> {
        val t = norm(text)
        val out = mutableListOf<Long>()
        fun nearSale(start: Int) = t.substring(maxOf(0, start - 220), start).let { "vend" in it || "bigliett" in it || "disponibilit" in it }
        // "a partire dalle ore 15 di martedì 11 agosto"
        Regex("(?:dalle|alle)\\s+ore\\s+(\\d{1,2})(?:[.:](\\d{2}))?\\s+(?:di\\s+)?(?:\\p{L}+\\s+)?(\\d{1,2})\\s+(luglio|agosto|settembre)")
            .findAll(t).forEach { m ->
                if (nearSale(m.range.first)) at(year, m.groupValues[4], m.groupValues[3], m.groupValues[1], m.groupValues[2])?.let(out::add)
            }
        // "dal 24 agosto alle 15"
        Regex("dal(?:l')?\\s*(\\d{1,2})\\s+(luglio|agosto|settembre)(?:\\s+\\d{4})?,?\\s+(?:alle|dalle)\\s+(?:ore\\s+)?(\\d{1,2})(?:[.:](\\d{2}))?")
            .findAll(t).forEach { m ->
                if (nearSale(m.range.first)) at(year, m.groupValues[2], m.groupValues[1], m.groupValues[3], m.groupValues[4])?.let(out::add)
            }
        return out
    }

    /** "Dal 2 al 12 settembre 2026" → (2026-09-02, 2026-09-12) */
    fun festDates(text: String, year: Int): Pair<String, String>? {
        val m = Regex("(?:dal|dall')\\s*(\\d{1,2})\\s*(?:al|-|–)\\s*(\\d{1,2})\\s+settembre\\s+$year").find(norm(text)) ?: return null
        return runCatching {
            LocalDate.of(year, 9, m.groupValues[1].toInt()).toString() to LocalDate.of(year, 9, m.groupValues[2].toInt()).toString()
        }.getOrNull()
    }

    /** Testo dell'articolo (solo il corpo della notizia). */
    private fun articleText(url: String): String {
        val html = get(url)
        val body = Regex("<article.*?</article>", RegexOption.DOT_MATCHES_ALL).find(html)?.value ?: html
        return Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ")
    }

    /**
     * Controlla il feed, aggiorna lo stato e manda le notifiche nuove.
     * [rssOverride] serve solo al collaudo (feed finto).
     */
    suspend fun check(ctx: Context, year: Int, notify: Boolean = true, rssOverride: String? = null): FestivalState = withContext(Dispatchers.IO) {
        val old = load(ctx)?.takeIf { it.year == year }
        val now = Instant.now().toString()
        val rss = rssOverride ?: runCatching { get(RSS) }.getOrElse {
            val s = (old ?: FestivalState(year, emptyList(), null, null, null, null, false, emptySet(), emptySet())).copy(checkedAt = now, ok = false)
            save(ctx, s)
            return@withContext s
        }
        val items = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(rss).map { it.groupValues[1] }.toList()
        val fresh = items.mapNotNull { it ->
            val title = tag(it, "title"); val desc = tag(it, "description")
            if (!isEdition("$title $desc", year)) return@mapNotNull null
            val link = tag(it, "link")
            // conta prima il titolo: "programma ... per il pubblico" parla anche di vendita, ma è il programma
            val kind = kindOf(title).takeIf { k -> k != "altro" } ?: kindOf("$title $desc")
            Triple(FestivalNews(tag(it, "guid").ifBlank { link }, kind, title, link, tag(it, "pubDate")), desc, link)
        }
        var saleAt = old?.saleAt
        var dates = old?.festStart?.let { st -> old.festEnd?.let { st to it } }
        val read = (old?.read ?: emptySet()).toMutableSet()
        for ((n, desc, link) in fresh) {
            // il corpo si legge una volta sola, e solo per gli annunci che possono contenere orari o date
            val text = if (n.id !in read && n.kind != "altro" && link.startsWith("http")) {
                runCatching { articleText(link) }.onSuccess { read += n.id }.getOrDefault(desc)
            } else desc
            val full = "${n.title} $desc $text"
            val times = saleTimes(full, year)
            if (times.isNotEmpty()) {
                val nowMs = System.currentTimeMillis()
                // la prossima apertura; se sono tutte passate, la più recente
                val pick = times.filter { it > nowMs - 3_600_000 }.minOrNull() ?: times.max()
                if (saleAt == null || pick > nowMs - 3_600_000 || pick > saleAt) saleAt = pick
            }
            festDates(full, year)?.let { dates = it }
        }
        val news = (fresh.map { it.first } + (old?.news ?: emptyList())).distinctBy { it.id }.take(20)
        var state = FestivalState(year, news, saleAt, dates?.first, dates?.second, now, true, old?.notified ?: emptySet(), read)
        if (notify) state = notifyNews(ctx, state)
        saleAt?.let { FestivalAlarm.schedule(ctx, it) }
        save(ctx, state)
        state
    }

    private fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun ensureChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Mostra del Cinema", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Film annunciati, programma per il pubblico, apertura della vendita dei biglietti"
            }
        )
    }

    fun post(ctx: Context, id: Int, title: String, text: String, url: String) {
        if (!canNotify(ctx)) return
        ensureChannel(ctx)
        val pi = PendingIntent.getActivity(
            ctx, id, Intent(Intent.ACTION_VIEW, Uri.parse(url)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_lion)
            .setColor(0xFFD4AF5F.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }

    /** Una notifica per ogni annuncio importante non ancora segnalato. */
    private fun notifyNews(ctx: Context, s: FestivalState): FestivalState {
        val repo = Repo(ctx)
        if (!repo.notificationsOn) return s
        val stay = repo.cached()
        val sent = s.notified.toMutableSet()
        for (n in s.news.reversed()) {
            if (n.id in sent || n.kind in setOf("altro", "abbonamenti")) continue
            val sale = s.saleAt?.takeIf { it > System.currentTimeMillis() }?.let { " Vendita online: ${saleLabel(it)}, avviso 30 minuti prima." } ?: ""
            val (title, extra) = when (n.kind) {
                "biglietti" -> "Biglietti della Mostra" to sale.ifEmpty { " Apri la pagina per i dettagli." }
                "programma" -> "Uscito il programma per il pubblico" to " Scegliete i film con Linda prima dell'apertura.$sale"
                "selezione" -> "Annunciati i film della Mostra ${s.year}" to " Guardate insieme la selezione ufficiale."
                else -> {
                    val warn = if (stay != null && s.festStart != null && s.festEnd != null &&
                        (stay.checkIn < s.festStart || stay.checkOut > LocalDate.parse(s.festEnd).plusDays(1).toString())
                    ) " Attenzione: il soggiorno è fuori da queste date." else " Il soggiorno è dentro le date."
                    "Date della Mostra ${s.year}" to (if (s.festStart != null) warn else "")
                }
            }
            post(ctx, n.id.hashCode(), title, n.title + "." + extra, n.link)
            sent += n.id
        }
        return s.copy(notified = sent)
    }
}

/** Sveglie per l'apertura della vendita: 30 minuti prima e all'ora esatta. */
class FestivalAlarm : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Repo(ctx).notificationsOn) return
        val at = intent.getLongExtra("at", 0L)
        if (intent.getStringExtra("kind") == "pre")
            FestivalWatch.post(ctx, 9001, "Tra 30 minuti aprono i biglietti della Mostra",
                "Apertura alle ${saleLabel(at).substringAfter("ore ")}. Entra su Vivaticket, tieni pronti i nomi tuoi e di Linda e la carta.", TICKETS_URL)
        else
            FestivalWatch.post(ctx, 9002, "Biglietti della Mostra in vendita adesso",
                "Prima la Sala Grande delle 19:30, poi le altre sale. Tocca per aprire Vivaticket.", TICKETS_URL)
    }

    companion object {
        fun schedule(ctx: Context, saleAt: Long) {
            val am = ctx.getSystemService(AlarmManager::class.java)
            val now = System.currentTimeMillis()
            listOf("pre" to saleAt - 30 * 60_000L, "open" to saleAt).forEachIndexed { i, (kind, t) ->
                val pi = PendingIntent.getBroadcast(
                    ctx, 700 + i,
                    Intent(ctx, FestivalAlarm::class.java).putExtra("kind", kind).putExtra("at", saleAt),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                if (t <= now) { am.cancel(pi); return@forEachIndexed }
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
                else
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
            }
        }
    }
}

/** Controllo leggero (un feed RSS) ogni 2 ore, fino alla fine del soggiorno. */
class FestivalWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val stay = Repo(applicationContext).cached()
        if (stay != null && LocalDate.now(ROME).toString() > stay.checkOut) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(WORK)
            return Result.success()
        }
        FestivalWatch.check(applicationContext, festivalYear(stay))
        return Result.success()
    }

    companion object {
        private const val WORK = "festival_check"
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<FestivalWorker>(2, java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
