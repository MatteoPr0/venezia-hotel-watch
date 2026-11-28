package it.matteo.veneziahotel

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import java.util.concurrent.TimeUnit

/**
 * Ogni ~6 ore scarica il JSON (pochi KB) aggiornato in cloud da GitHub Actions, controlla
 * Booking per il Lido e notifica solo le novità rilevanti. Nessun servizio sempre attivo.
 */
class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val repo = Repo(applicationContext)
        if (!repo.notificationsOn) return Result.success()
        // Google (da GitHub), per tutti i weekend: se la rete non va si riprova più tardi
        val stays = runCatching { repo.fetchAll() }.getOrElse { repo.cachedAll().ifEmpty { return Result.retry() } }
        // Booking per il Lido, dal telefono (Google lì non ha tariffe), e gli altri siti
        // (Airbnb, Holidu, Trip.com): uno dopo l'altro, ognuno ricorda il suo risultato.
        // Prima il weekend controllato da più tempo; Android concede ~10 minuti a ogni giro,
        // quindi dopo 6 minuti non si iniziano altre letture (le altre al giro successivo).
        val started = System.currentTimeMillis()
        val order = stays.indices.sortedByDescending { i ->
            SITES.maxOfOrNull { siteAgeMinutes(applicationContext, stays[i], it, i) ?: Long.MAX_VALUE } ?: 0L
        }
        for (i in order) {
            for (site in SITES) {
                if (System.currentTimeMillis() - started > 6 * 60_000L) break
                checkSite(applicationContext, stays[i], site, force = false, minMinutes = 60, stayIdx = i)
            }
        }
        stays.forEachIndexed { i, g ->
            notifyNew(applicationContext, repo, mergeAll(g, loadSites(applicationContext, i)), i, stays.size > 1)
        }
        return Result.success()
    }

    companion object {
        const val CHANNEL = "hotel_updates"
        private const val WORK = "hotel_check"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<CheckWorker>(6, TimeUnit.HOURS, 1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Novità hotel", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Nuovi hotel, cali di prezzo e camere tornate disponibili"
                }
            )
        }

        /**
         * Notifica gli eventi più recenti dell'ultimo visto per quel soggiorno.
         * Al primo avvio (o col primo controllo di un nuovo weekend) segna tutto come già visto.
         */
        fun notifyNew(ctx: Context, repo: Repo, snap: Snapshot, stayIdx: Int = 0, showStay: Boolean = false) {
            val newest = snap.events.firstOrNull()?.time ?: return
            val last = repo.lastNotified(stayIdx)
            if (last.isEmpty()) {
                repo.setLastNotified(stayIdx, newest)
                return
            }
            val fresh = snap.events.filter { it.time > last }
            repo.setLastNotified(stayIdx, maxOf(last, newest))
            val relevant = fresh.filter { e ->
                e.type in setOf("new", "back", "drop", "priced") && e.total != null &&
                    (!repo.notifyOnlyWithinMax || e.total <= snap.maxFor(e.zone))
            }
            if (relevant.isEmpty()) return
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return

            ensureChannel(ctx)
            val lines = relevant.take(6).map { e ->
                val price = e.total?.let { "€ $it" } ?: "prezzo n.d."
                val zone = zoneLabel(e.zone)
                when (e.type) {
                    "new" -> "Nuovo · ${e.name} ($zone) · $price"
                    "back" -> "Di nuovo libero · ${e.name} · $price"
                    "priced" -> "Prezzi usciti · ${e.name} ($zone) · $price"
                    else -> "↓ ${e.name} · $price (era € ${e.prevTotal})"
                }
            }
            val inBudget = relevant.count { it.total != null && it.total <= snap.budgetTarget }
            val weekend = if (showStay) " · " + shortStay(snap) else ""
            val title = when {
                relevant.size == 1 -> lines.first().substringBefore(" · ").let { "$it · Venezia '27$weekend" }
                else -> "${relevant.size} novità hotel per la Mostra$weekend"
            }
            val text = if (relevant.size == 1) lines.first().substringAfter(" · ")
            else if (inBudget > 0) "$inBudget entro € ${snap.budgetTarget}" else lines.first()

            val pi = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val style = NotificationCompat.InboxStyle().also { s -> lines.forEach { s.addLine(it) } }
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_lion)
                .setColor(0xFFD4AF5F.toInt())
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(style)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(ctx).notify((newest + stayIdx).hashCode(), n)
        }
    }
}

/** "2–5 set" */
fun shortStay(s: Snapshot): String = runCatching {
    val a = java.time.LocalDate.parse(s.checkIn)
    val b = java.time.LocalDate.parse(s.checkOut)
    val m = b.format(java.time.format.DateTimeFormatter.ofPattern("MMM", java.util.Locale.ITALIAN))
    if (a.month == b.month) "${a.dayOfMonth}–${b.dayOfMonth} $m" else "${a.dayOfMonth} ${a.format(java.time.format.DateTimeFormatter.ofPattern("MMM", java.util.Locale.ITALIAN))}–${b.dayOfMonth} $m"
}.getOrDefault("${s.checkIn}→${s.checkOut}")

fun zoneLabel(z: String) = when (z) {
    "lido" -> "Lido"
    "centro" -> "Centro storico"
    else -> z.replaceFirstChar { it.uppercase() }
}
