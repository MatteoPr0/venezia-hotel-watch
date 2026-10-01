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
 * Leggero: ogni ~6 ore scarica il JSON (pochi KB) aggiornato in cloud da GitHub Actions
 * e notifica solo le novità rilevanti. Nessun servizio sempre attivo.
 */
class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val repo = Repo(applicationContext)
        if (!repo.notificationsOn) return Result.success()
        val snap = runCatching { repo.fetch() }.getOrElse { return Result.retry() }
        notifyNew(applicationContext, repo, snap)
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

        /** Notifica gli eventi più recenti dell'ultimo visto. Al primo avvio segna tutto come già visto. */
        fun notifyNew(ctx: Context, repo: Repo, snap: Snapshot) {
            val newest = snap.events.firstOrNull()?.time ?: return
            val last = repo.lastNotifiedEvent
            if (last.isEmpty()) {
                repo.lastNotifiedEvent = newest
                return
            }
            val fresh = snap.events.filter { it.time > last }
            repo.lastNotifiedEvent = maxOf(last, newest)
            val relevant = fresh.filter { e ->
                e.type in setOf("new", "back", "drop") &&
                    (!repo.notifyOnlyWithinMax || e.total == null || e.total <= snap.budgetMax)
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
                    else -> "↓ ${e.name} · $price (era € ${e.prevTotal})"
                }
            }
            val inBudget = relevant.count { it.total != null && it.total <= snap.budgetTarget }
            val title = when {
                relevant.size == 1 -> lines.first().substringBefore(" · ").let { "$it · Venezia '27" }
                else -> "${relevant.size} novità hotel per la Mostra"
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
            NotificationManagerCompat.from(ctx).notify(newest.hashCode(), n)
        }
    }
}

fun zoneLabel(z: String) = when (z) {
    "lido" -> "Lido"
    "centro" -> "Centro storico"
    else -> z.replaceFirstChar { it.uppercase() }
}
