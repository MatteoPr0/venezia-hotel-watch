@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package it.matteo.veneziahotel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.net.URLEncoder
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val IT = Locale.ITALIAN
private fun eur(v: Int?) = if (v == null) "—" else "€ " + NumberFormat.getIntegerInstance(IT).format(v)

private fun ago(iso: String?): String {
    if (iso.isNullOrBlank()) return "mai"
    return runCatching {
        val d = Duration.between(Instant.parse(iso), Instant.now())
        when {
            d.toMinutes() < 1 -> "adesso"
            d.toMinutes() < 60 -> "${d.toMinutes()} min fa"
            d.toHours() < 24 -> "${d.toHours()} h fa"
            else -> "${d.toDays()} g fa"
        }
    }.getOrDefault(iso)
}

private fun dayLabel(iso: String): String = runCatching {
    val d = Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    when (d) {
        today -> "Oggi"
        today.minusDays(1) -> "Ieri"
        else -> d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", IT)).replaceFirstChar { it.uppercase() }
    }
}.getOrDefault(iso.take(10))

private fun stayLabel(s: Snapshot): String = runCatching {
    val f = DateTimeFormatter.ofPattern("EEE d", IT)
    val a = LocalDate.parse(s.checkIn)
    val b = LocalDate.parse(s.checkOut)
    "${a.format(f)} → ${b.format(f)} ${b.format(DateTimeFormatter.ofPattern("MMMM", IT))} · ${s.nights} notti · ${s.adults} adulti"
}.getOrDefault("${s.checkIn} → ${s.checkOut}")

private fun priceColor(total: Int?, s: Snapshot) = when {
    total == null -> C.Muted
    total <= s.budgetTarget -> C.Good
    total <= s.budgetMax -> C.Gold
    else -> C.Bad
}

private fun open(ctx: Context, url: String) = runCatching {
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun dalLabel(d: String) = if (d.first() == '8' || d.startsWith("11 ")) "dall'$d" else "dal $d"

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/* ───────────────────────────── Root ───────────────────────────── */

@Composable
fun AppScreen(vm: MainVm) {
    val s = vm.snap
    val recent = s?.events?.filter { !(it.type == "new" && it.total == null) }?.count {
        runCatching { Duration.between(Instant.parse(it.time), Instant.now()).toHours() < 48 }.getOrDefault(false)
    } ?: 0
    Scaffold(
        containerColor = C.Bg,
        bottomBar = {
            NavigationBar(containerColor = C.Surface, tonalElevation = 0.dp) {
                val colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = C.Bg, selectedTextColor = C.Gold, indicatorColor = C.Gold,
                    unselectedIconColor = C.Muted, unselectedTextColor = C.Muted,
                )
                NavigationBarItem(vm.tab == 0, { vm.tab = 0 }, { Icon(Icons.Rounded.Hotel, null) },
                    label = { Text("Hotel") }, colors = colors)
                NavigationBarItem(vm.tab == 3, { vm.tab = 3 }, { Icon(Icons.Rounded.Star, null) },
                    label = { Text("Scelte") }, colors = colors)
                NavigationBarItem(vm.tab == 1, { vm.tab = 1 }, {
                    BadgedBox(badge = { if (recent > 0) Badge(containerColor = C.Bad, contentColor = C.Bg) { Text("$recent") } }) {
                        Icon(Icons.Rounded.NotificationsActive, null)
                    }
                }, label = { Text("Novità") }, colors = colors)
                NavigationBarItem(vm.tab == 2, { vm.tab = 2 }, { Icon(Icons.Rounded.Tune, null) },
                    label = { Text("Impostazioni") }, colors = colors)
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (vm.tab) {
                0 -> HotelsTab(vm)
                1 -> EventsTab(vm)
                3 -> CuratedTab(vm)
                else -> SettingsTab(vm)
            }
        }
    }
    vm.selected?.let { h -> s?.let { HotelSheet(h, it) { vm.selected = null } } }
}

/* ───────────────────────────── Hotel ───────────────────────────── */

@Composable
private fun HotelsTab(vm: MainVm) {
    val s = vm.snap
    val list = vm.visibleHotels()
    PullToRefreshBox(isRefreshing = vm.loading, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Header(vm, s) }
            if (s != null) {
                if (s.waitingUntil == null) item { BudgetCard(s) } else item { WaitingBanner(s) }
                item { Filters(vm, s) }
            }
            val priced = list.filter { it.total != null }
            val unpriced = list.filter { it.total == null }.sortedWith(
                compareBy<Hotel>({ !it.available }, { it.zone != "lido" }, { if (it.zone == "lido") distPalazzoM(it.lat, it.lon) ?: 99999 else 0 },
                    { -(it.rating ?: 0.0) })
            )
            if (s == null || list.isEmpty()) {
                item { EmptyState(vm, s) }
            } else {
                items(priced, key = { it.id }) { h -> HotelCard(h, s) { vm.selected = h } }
                if (priced.isNotEmpty()) item {
                    Text(
                        "Prezzi totali per ${s.nights} notti, 2 persone, da Google Hotels. Tassa di soggiorno esclusa.",
                        style = MaterialTheme.typography.bodySmall, color = C.Faint,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (unpriced.isNotEmpty()) {
                    item {
                        SectionTitle(
                            if (s.waitingUntil != null) "Hotel trovati in zona (${unpriced.size})" else "Senza tariffe su Google (${unpriced.size})",
                            if (s.waitingUntil != null) "Prezzi da Google appena si apre la finestra. Intanto puoi controllare sul sito di ciascun hotel."
                            else "Google non ha prezzi per queste date: possono essere chiusi o vendere solo dal proprio sito."
                        )
                    }
                    items(unpriced, key = { "u" + it.id }) { h -> CompactHotelRow(h) { vm.selected = h } }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, sub: String) {
    Column(Modifier.padding(top = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = C.Text)
        Spacer(Modifier.height(4.dp))
        Text(sub, style = MaterialTheme.typography.bodySmall, color = C.Muted)
    }
}

@Composable
private fun WaitingBanner(s: Snapshot) {
    val dl = runCatching { LocalDate.parse(s.waitingUntil).format(DateTimeFormatter.ofPattern("d MMMM", IT)) }.getOrDefault(s.waitingUntil ?: "")
    Surface(color = C.Surface, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, C.Line), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.HourglassTop, null, tint = C.Gold, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Tariffe ${dalLabel(dl)}", style = MaterialTheme.typography.titleLarge, color = C.Text)
                Text("Google pubblica i prezzi solo 330 giorni prima del check-in. Da quel giorno i controlli partono da soli e ti avviso.",
                    style = MaterialTheme.typography.bodySmall, color = C.Muted)
            }
        }
    }
}

@Composable
private fun CompactHotelRow(h: Hotel, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().alpha(if (h.available) 1f else 0.45f).clip(RoundedCornerShape(18.dp)).background(C.Surface)
            .clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(C.Surface2)) {
            if (h.image != null) AsyncImage(h.image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(h.name, color = C.Text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                h.stars?.let { Text("★".repeat(it) + "  ", color = C.Gold, fontSize = 11.sp) }
                h.rating?.let { Text(String.format(IT, "%.1f", it), color = C.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                h.reviews?.let { Text(" ($it)", color = C.Faint, fontSize = 12.sp) }
                Text("  ·  " + zoneLabel(h.zone), color = zoneColor(h.zone), fontSize = 12.sp)
            }
            palazzoLabel(h.lat, h.lon)?.takeIf { h.zone == "lido" }?.let { Text(it, color = C.Muted, fontSize = 12.sp) }
        }
        IconButton(onClick = {
            open(ctx, h.link ?: "https://www.google.com/search?q=" + enc(h.name + " Venezia"))
        }) { Icon(Icons.Rounded.Language, "Sito", tint = C.Gold) }
    }
}

@Composable
private fun Header(vm: MainVm, s: Snapshot?) {
    Column(Modifier.padding(top = 8.dp)) {
        Text("MOSTRA DEL CINEMA · LIDO", style = MaterialTheme.typography.labelSmall, color = C.Gold)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Venezia ’27", style = MaterialTheme.typography.displaySmall, color = C.Text, modifier = Modifier.weight(1f))
            IconButton(onClick = vm::refresh) { Icon(Icons.Rounded.Refresh, "Aggiorna", tint = C.Muted) }
        }
        if (s != null) {
            Text(stayLabel(s), style = MaterialTheme.typography.bodyMedium, color = C.Muted)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (s.runOk && vm.error == null) C.Good else C.Warn))
                Spacer(Modifier.width(8.dp))
                val extra = s.searchesLeft?.let { " · $it ricerche SerpApi rimaste" } ?: ""
                Text(
                    when {
                        vm.error != null -> "Offline · dati di ${ago(s.updatedAt)}"
                        s.waitingUntil != null -> "In attesa dell'apertura tariffe · controllato ${ago(s.updatedAt)}"
                        !s.runOk -> "Ultimo controllo con errori · ${ago(s.updatedAt)}"
                        else -> "Controllato ${ago(s.updatedAt)}$extra"
                    },
                    style = MaterialTheme.typography.bodySmall, color = C.Faint
                )
            }
        }
    }
}

@Composable
private fun BudgetCard(s: Snapshot) {
    val avail = s.hotels.filter { it.available && it.total != null }
    val inTarget = avail.count { it.total!! <= s.budgetTarget }
    val inMax = avail.count { it.total!! <= s.budgetMax }
    val best = avail.filter { it.zone == "lido" }.minByOrNull { it.total!! }
    Surface(
        color = C.Surface, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, C.Line),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Row {
                Stat("$inTarget", "entro ${eur(s.budgetTarget)}", C.Good, Modifier.weight(1f))
                Stat("$inMax", "entro ${eur(s.budgetMax)}", C.Gold, Modifier.weight(1f))
                Stat(best?.total?.let { eur(it) } ?: "—", if (best != null) "miglior Lido" else "Lido: ancora nessuna tariffa", C.Lido, Modifier.weight(1.2f))
            }
            val waiting = s.hotels.count { it.available && it.total == null }
            if (waiting > 0) {
                Spacer(Modifier.height(10.dp))
                Text("$waiting hotel senza tariffe su Google per queste date: li trovi in fondo alla lista.",
                    style = MaterialTheme.typography.bodySmall, color = C.Faint)
            }
            if (best != null) {
                Spacer(Modifier.height(10.dp))
                Text(best.name, style = MaterialTheme.typography.bodySmall, color = C.Muted, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(value, fontFamily = Serif, fontSize = 26.sp, color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = C.Muted)
    }
}

@Composable
private fun Filters(vm: MainVm, s: Snapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Tutti", vm.zone == ZoneFilter.ALL) { vm.zone = ZoneFilter.ALL }
            Pill("Lido", vm.zone == ZoneFilter.LIDO, C.Lido) { vm.zone = ZoneFilter.LIDO }
            Pill("Centro storico", vm.zone == ZoneFilter.CENTRO, C.Centro) { vm.zone = ZoneFilter.CENTRO }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.waitingUntil == null) {
                Pill("≤ ${eur(s.budgetMax)}", vm.onlyWithinMax) { vm.onlyWithinMax = !vm.onlyWithinMax }
                Pill(if (vm.sort == SortBy.PRICE) "Prezzo ↑" else "Voto ↓", true, C.Muted) {
                    vm.sort = if (vm.sort == SortBy.PRICE) SortBy.RATING else SortBy.PRICE
                }
            }
            val gone = s.hotels.count { !it.available }
            if (gone > 0) Pill("Esauriti ($gone)", vm.showGone, C.Faint) { vm.showGone = !vm.showGone }
        }
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, accent: Color = C.Gold, onClick: () -> Unit) {
    val bg = if (selected) accent.copy(alpha = 0.16f) else Color.Transparent
    val border = if (selected) accent.copy(alpha = 0.7f) else C.Line
    Text(
        text,
        color = if (selected) accent else C.Muted,
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium, fontSize = 13.sp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
private fun HotelCard(h: Hotel, s: Snapshot, onClick: () -> Unit) {
    Surface(
        color = C.Surface, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, C.Line),
        modifier = Modifier.fillMaxWidth().alpha(if (h.available) 1f else 0.45f).clickable(onClick = onClick)
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(150.dp)) {
                if (h.image != null) {
                    AsyncImage(h.image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(C.Surface2, zoneColor(h.zone).copy(alpha = 0.25f)))))
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE60F0D0B)), startY = 60f)))
                Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    BadgeChip(h)
                    ZoneTag(h.zone)
                }
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(h.name, style = MaterialTheme.typography.titleLarge, color = C.Text, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        h.stars?.let { Text("★".repeat(it), color = C.Gold, fontSize = 12.sp, letterSpacing = 1.sp); Spacer(Modifier.width(8.dp)) }
                        h.rating?.let {
                            Text(String.format(IT, "%.1f", it), color = C.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(" (${h.reviews ?: 0})", color = C.Faint, fontSize = 12.sp)
                        }
                    }
                    if (h.zone == "lido") palazzoLabel(h.lat, h.lon)?.let { Text(it, color = C.Muted, fontSize = 12.sp) }
                    if (h.history.size >= 2) {
                        Spacer(Modifier.height(8.dp))
                        Sparkline(h.history.map { it.total }, Modifier.width(110.dp).height(22.dp))
                    }
                    if (!h.available) Text("Non più disponibile", color = C.Bad, fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(eur(h.total), fontFamily = Serif, fontSize = 26.sp, color = priceColor(h.total, s))
                    Text(
                        when {
                            h.total == null -> "nessuna tariffa per queste date"
                            h.total > s.budgetMax -> "sopra budget · ${eur(h.perNight)}/notte"
                            else -> "${eur(h.perNight)}/notte"
                        },
                        fontSize = 12.sp, color = C.Muted
                    )
                }
            }
        }
    }
}

@Composable
private fun BadgeChip(h: Hotel) {
    val (txt, col, icon) = when (h.badge) {
        "new" -> Triple("NUOVO", C.Gold, Icons.Rounded.AutoAwesome)
        "back" -> Triple("DI NUOVO LIBERO", C.Good, Icons.Rounded.EventAvailable)
        "priced" -> Triple("PREZZI USCITI", C.Gold, Icons.Rounded.Sell)
        "drop" -> Triple("${eur(-h.change)} IN MENO", C.Good, Icons.AutoMirrored.Rounded.TrendingDown)
        "up" -> Triple("+${eur(h.change)}", C.Bad, Icons.AutoMirrored.Rounded.TrendingUp)
        "gone" -> Triple("ESAURITO", C.Bad, Icons.Rounded.EventBusy)
        else -> return
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC0F0D0B)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = col, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(txt, color = col, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp))
    }
}

@Composable
private fun ZoneTag(zone: String) {
    Text(
        zoneLabel(zone).uppercase(), color = zoneColor(zone),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC0F0D0B))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
private fun Sparkline(values: List<Int>, modifier: Modifier) {
    Canvas(modifier) {
        val min = values.min().toFloat()
        val max = values.max().toFloat()
        val span = (max - min).takeIf { it > 0 } ?: 1f
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val p = Offset(i * step, size.height - (v - min) / span * size.height)
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        val down = values.last() <= values.first()
        drawPath(path, if (down) C.Good else C.Bad, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun EmptyState(vm: MainVm, s: Snapshot?) {
    val (title, body) = when {
        s == null && vm.loading -> "Carico…" to ""
        s == null -> "Nessun dato" to (vm.error?.let { "Non riesco a leggere il file dati ($it). Controlla l'indirizzo in Impostazioni." } ?: "")
        s.waitingUntil != null -> {
            val dl = runCatching { LocalDate.parse(s.waitingUntil).format(DateTimeFormatter.ofPattern("d MMMM yyyy", IT)) }.getOrDefault(s.waitingUntil)
            "Tariffe ${dalLabel(dl)}" to
                "Google pubblica i prezzi degli hotel solo fino a 330 giorni prima del check-in. ${dalLabel(dl).replaceFirstChar { it.uppercase() }} i controlli partono da soli due volte al giorno e ti avviso appena trovo qualcosa nel budget."
        }
        s.updatedAt == null -> "In attesa del primo controllo" to
            "Il controllo in cloud gira due volte al giorno. Puoi avviarlo subito da GitHub → Actions → “Controllo hotel” → Run workflow."
        s.hotels.none { it.total != null } && s.hotels.isNotEmpty() -> "Nessuna tariffa ancora" to
            "Google conosce ${s.hotels.size} hotel in zona ma nessuno ha ancora aperto le prenotazioni per queste date. Ti avviso appena succede."
        s.hotels.isEmpty() -> "Ancora niente sotto i ${eur((s.budgetMax * 1.25).toInt())}" to
            "Per settembre 2027 molti hotel non hanno ancora aperto le tariffe su Google: di solito compaiono 10–12 mesi prima. Continuo a controllare e ti avviso appena esce qualcosa."
        else -> "Nessun hotel con questi filtri" to "Prova a togliere qualche filtro."
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Bed, null, tint = C.GoldDim, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = C.Text)
        if (body.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = C.Muted,
                modifier = Modifier.padding(horizontal = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/* ───────────────────────────── Dettaglio ───────────────────────────── */

@Composable
private fun HotelSheet(h: Hotel, s: Snapshot, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C.Surface, sheetState = rememberModalBottomSheetState(true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            if (h.image != null) {
                AsyncImage(h.image, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(18.dp)))
                Spacer(Modifier.height(16.dp))
            }
            Row { ZoneTag(h.zone); Spacer(Modifier.width(8.dp)); BadgeChip(h) }
            Spacer(Modifier.height(10.dp))
            Text(h.name, style = MaterialTheme.typography.headlineSmall, color = C.Text)
            Row(verticalAlignment = Alignment.CenterVertically) {
                h.stars?.let { Text("★".repeat(it) + "  ", color = C.Gold, fontSize = 13.sp) }
                h.rating?.let { Text(String.format(IT, "%.1f · %d recensioni", it, h.reviews ?: 0), color = C.Muted, fontSize = 13.sp) }
            }
            Spacer(Modifier.height(18.dp))
            if (h.total != null) Row(verticalAlignment = Alignment.Bottom) {
                Text(eur(h.total), fontFamily = Serif, fontSize = 34.sp, color = priceColor(h.total, s))
                Spacer(Modifier.width(10.dp))
                Text("${s.nights} notti · ${eur(h.perNight)}/notte", color = C.Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 6.dp))
            }
            if (h.zone == "lido") palazzoLabel(h.lat, h.lon)?.let { Text(it, color = C.Muted, fontSize = 13.sp) }
            if (h.total == null) Text(
                if (s.waitingUntil != null) "Prezzi Google disponibili ${dalLabel(runCatching { LocalDate.parse(s.waitingUntil).format(DateTimeFormatter.ofPattern("d MMMM", IT)) }.getOrDefault(""))}. Intanto verifica sul sito dell'hotel."
                else "Nessuna tariffa su Google per queste date: verifica sul sito dell'hotel.",
                color = C.Warn, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)
            )
            val delta = (h.total ?: 0) - s.budgetTarget
            if (h.total != null) Text(
                if (delta <= 0) "${eur(-delta)} sotto il budget ideale" else "${eur(delta)} sopra il budget ideale",
                color = if (delta <= 0) C.Good else if (h.total <= s.budgetMax) C.Gold else C.Bad, fontSize = 13.sp
            )

            if (h.history.size >= 2) {
                Spacer(Modifier.height(20.dp))
                Text("ANDAMENTO PREZZO", style = MaterialTheme.typography.labelSmall, color = C.Muted)
                Spacer(Modifier.height(8.dp))
                PriceChart(h.history, s, Modifier.fillMaxWidth().height(140.dp))
                Text("Minimo visto: ${eur(h.minTotal)}  ·  linee: ${eur(s.budgetTarget)} ideale, ${eur(s.budgetMax)} max",
                    color = C.Faint, fontSize = 12.sp)
            }

            Spacer(Modifier.height(20.dp))
            Text("PRENOTA / VERIFICA", style = MaterialTheme.typography.labelSmall, color = C.Muted)
            Spacer(Modifier.height(10.dp))
            val q = enc("${h.name} Venezia")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinkButton("Booking", Icons.Rounded.Hotel, primary = true) {
                    open(ctx, "https://www.booking.com/searchresults.it.html?ss=$q&checkin=${s.checkIn}&checkout=${s.checkOut}" +
                        "&group_adults=${s.adults}&no_rooms=1&group_children=0")
                }
                LinkButton("Google Hotels", Icons.Rounded.TravelExplore) {
                    open(ctx, "https://www.google.com/travel/search?q=$q&hl=it&gl=it")
                }
                h.link?.let { l -> LinkButton("Sito hotel", Icons.Rounded.Language) { open(ctx, l) } }
                if (h.lat != null && h.lon != null) LinkButton("Mappa", Icons.Rounded.Map) {
                    open(ctx, "geo:${h.lat},${h.lon}?q=${h.lat},${h.lon}(${enc(h.name)})")
                }
            }
            if (h.amenities.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text("SERVIZI", style = MaterialTheme.typography.labelSmall, color = C.Muted)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    h.amenities.forEach {
                        Text(it, color = C.Muted, fontSize = 12.sp, modifier = Modifier
                            .border(1.dp, C.Line, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Visto per la prima volta ${ago(h.firstSeen)} · ultimo controllo ${ago(h.lastSeen)}",
                color = C.Faint, fontSize = 12.sp)
        }
    }
}

@Composable
private fun LinkButton(text: String, icon: ImageVector, primary: Boolean = false, small: Boolean = false, onClick: () -> Unit) {
    val pad = if (small) PaddingValues(horizontal = 12.dp, vertical = 0.dp) else ButtonDefaults.ContentPadding
    val mod = if (small) Modifier.height(36.dp) else Modifier
    val ts = if (small) 13.sp else 14.sp
    if (primary) {
        Button(onClick, mod, colors = ButtonDefaults.buttonColors(containerColor = C.Gold, contentColor = C.Bg), contentPadding = pad) {
            Icon(icon, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(text, fontSize = ts)
        }
    } else {
        OutlinedButton(onClick, mod, border = BorderStroke(1.dp, C.Line),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = C.Text), contentPadding = pad) {
            Icon(icon, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(text, fontSize = ts)
        }
    }
}

@Composable
private fun PriceChart(points: List<PricePoint>, s: Snapshot, modifier: Modifier) {
    val values = points.map { it.total }
    Canvas(modifier.padding(vertical = 8.dp)) {
        val lo = minOf(values.min(), s.budgetTarget) * 0.95f
        val hi = maxOf(values.max(), s.budgetMax) * 1.03f
        fun y(v: Float) = size.height - (v - lo) / (hi - lo) * size.height
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
        drawLine(C.Good.copy(alpha = .5f), Offset(0f, y(s.budgetTarget.toFloat())), Offset(size.width, y(s.budgetTarget.toFloat())), 1.dp.toPx(), pathEffect = dash)
        drawLine(C.Gold.copy(alpha = .5f), Offset(0f, y(s.budgetMax.toFloat())), Offset(size.width, y(s.budgetMax.toFloat())), 1.dp.toPx(), pathEffect = dash)
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val p = Offset(i * step, y(v.toFloat()))
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, C.Text, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(C.Gold, 4.dp.toPx(), Offset((values.size - 1) * step, y(values.last().toFloat())))
    }
}

/* ───────────────────────────── Novità ───────────────────────────── */

@Composable
private fun EventsTab(vm: MainVm) {
    val s = vm.snap
    val events = s?.events.orEmpty().filter { !(it.type == "new" && it.total == null) }
    PullToRefreshBox(isRefreshing = vm.loading, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("NOVITÀ", style = MaterialTheme.typography.labelSmall, color = C.Gold, modifier = Modifier.padding(top = 8.dp))
                Text("Diario dei controlli", style = MaterialTheme.typography.headlineSmall, color = C.Text)
                Spacer(Modifier.height(6.dp))
            }
            if (events.isEmpty()) item {
                Text("Ancora nessun evento. Qui vedrai nuovi hotel, cali di prezzo e camere esaurite.",
                    color = C.Muted, style = MaterialTheme.typography.bodyMedium)
            }
            var lastDay = ""
            events.forEach { e ->
                val day = dayLabel(e.time)
                if (day != lastDay) {
                    lastDay = day
                    item(key = "d$day$e") {
                        Text(day, color = C.Muted, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 10.dp))
                    }
                }
                item { EventRow(e, s!!) { s.hotels.find { it.id == e.id }?.let { vm.selected = it } } }
            }
        }
    }
}

@Composable
private fun EventRow(e: HotelEvent, s: Snapshot, onClick: () -> Unit) {
    val (icon, col, label) = when (e.type) {
        "new" -> Triple(Icons.Rounded.AutoAwesome, C.Gold, "Nuovo hotel")
        "drop" -> Triple(Icons.AutoMirrored.Rounded.TrendingDown, C.Good, "Prezzo sceso da ${eur(e.prevTotal)}")
        "back" -> Triple(Icons.Rounded.EventAvailable, C.Good, "Di nuovo disponibile")
        "priced" -> Triple(Icons.Rounded.Sell, C.Gold, "Prezzi pubblicati")
        else -> Triple(Icons.Rounded.EventBusy, C.Bad, "Non più disponibile")
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Surface).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(col.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = col, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, color = C.Text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$label · ${zoneLabel(e.zone)}", color = C.Muted, fontSize = 12.sp)
        }
        Text(eur(e.total), fontFamily = Serif, fontSize = 18.sp, color = if (e.type == "gone") C.Faint else priceColor(e.total, s))
    }
}

/* ───────────────────────────── Impostazioni ───────────────────────────── */

@Composable
private fun SettingsTab(vm: MainVm) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(vm.repo.dataUrl) }
    var notify by remember { mutableStateOf(vm.repo.notificationsOn) }
    var withinMax by remember { mutableStateOf(vm.repo.notifyOnlyWithinMax) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text("IMPOSTAZIONI", style = MaterialTheme.typography.labelSmall, color = C.Gold, modifier = Modifier.padding(top = 8.dp))
        Text("Come funziona", style = MaterialTheme.typography.headlineSmall, color = C.Text)
        Spacer(Modifier.height(8.dp))
        Text(
            "Due volte al giorno GitHub Actions interroga Google Hotels (via SerpApi) per Lido e centro storico e salva i risultati. " +
                "L'app legge quel file ogni ~6 ore in background e ti notifica nuovi hotel, cali di prezzo e camere tornate libere. " +
                "Date e budget si cambiano in config.json nel repository.",
            color = C.Muted, style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))
        SettingSwitch("Notifiche", "Avvisami delle novità", notify) { notify = it; vm.repo.notificationsOn = it }
        SettingSwitch("Solo entro il budget massimo", "Ignora novità sopra ${eur(vm.snap?.budgetMax ?: 1200)}", withinMax) {
            withinMax = it; vm.repo.notifyOnlyWithinMax = it
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
            border = BorderStroke(1.dp, C.Line), colors = ButtonDefaults.outlinedButtonColors(contentColor = C.Text)
        ) { Icon(Icons.Rounded.BatteryChargingFull, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Ottimizzazione batteria") }
        Text("Su OnePlus imposta l'app su “Non ottimizzare” se le notifiche non arrivano.",
            color = C.Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))

        Spacer(Modifier.height(24.dp))
        Text("FILE DATI", style = MaterialTheme.typography.labelSmall, color = C.Muted)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            url, { url = it }, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall,
            label = { Text("URL hotels.json") }, singleLine = false,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.repo.dataUrl = url; vm.refresh() },
                colors = ButtonDefaults.buttonColors(containerColor = C.Gold, contentColor = C.Bg)) { Text("Salva e aggiorna") }
            OutlinedButton(onClick = {
                val repoUrl = Regex("raw.githubusercontent.com/([^/]+)/([^/]+)/").find(url)
                    ?.let { "https://github.com/${it.groupValues[1]}/${it.groupValues[2]}/actions" }
                repoUrl?.let { open(ctx, it) }
            }, border = BorderStroke(1.dp, C.Line), colors = ButtonDefaults.outlinedButtonColors(contentColor = C.Text)) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("GitHub Actions")
            }
        }
        vm.snap?.runErrors?.takeIf { it.isNotEmpty() }?.let { errs ->
            Spacer(Modifier.height(16.dp))
            Text("Errori ultimo controllo", color = C.Warn, fontWeight = FontWeight.Medium)
            errs.forEach { Text(it, color = C.Muted, fontSize = 12.sp) }
        }
        Spacer(Modifier.height(24.dp))
        Text("Versione ${BuildConfig.VERSION_NAME}", color = C.Faint, fontSize = 12.sp)
    }
}

@Composable
private fun SettingSwitch(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Text, style = MaterialTheme.typography.titleMedium)
            Text(sub, color = C.Muted, fontSize = 12.sp)
        }
        Switch(value, onChange, colors = SwitchDefaults.colors(
            checkedThumbColor = C.Bg, checkedTrackColor = C.Gold, uncheckedTrackColor = C.Surface2, uncheckedBorderColor = C.Line))
    }
}


/* ───────────────────────────── Scelte migliori ───────────────────────────── */

@Composable
private fun CuratedTab(vm: MainVm) {
    val c = vm.curated
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("SELEZIONE", style = MaterialTheme.typography.labelSmall, color = C.Gold, modifier = Modifier.padding(top = 8.dp))
            Text("Le migliori scelte", style = MaterialTheme.typography.headlineSmall, color = C.Text)
            Spacer(Modifier.height(6.dp))
            Text(c?.intro ?: "Selezione non disponibile.", style = MaterialTheme.typography.bodySmall, color = C.Muted)
        }
        c?.groups?.forEach { g ->
            item(key = "g" + g.title) { SectionTitle(g.title, g.subtitle) }
            items(g.hotels, key = { "c" + it.match }) { h -> CuratedCard(h, vm) }
        }
        item {
            Text("Aggiornata il ${c?.updated ?: "—"} · distanze in linea d'aria dal Palazzo del Cinema, tempi a piedi stimati.",
                style = MaterialTheme.typography.bodySmall, color = C.Faint, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun CuratedCard(h: CuratedHotel, vm: MainVm) {
    val ctx = LocalContext.current
    val s = vm.snap
    val live = s?.hotels?.find { it.id == h.match }
    @Suppress("UNUSED_VARIABLE") val tick = vm.checkedTick
    val checked = checkedOn(ctx, h.match)
    val (fitText, fitCol) = when (h.fit) {
        "budget" -> "Nel budget (stima)" to C.Good
        "limite" -> "Al limite (stima)" to C.Gold
        else -> "Sopra budget" to C.Bad
    }
    Surface(color = C.Surface, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, C.Line), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(h.name, style = MaterialTheme.typography.titleLarge, color = C.Text)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        h.stars?.let { Text("★".repeat(it) + "  ", color = C.Gold, fontSize = 12.sp) }
                        h.rating?.let { Text(String.format(IT, "%.1f", it), color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                        h.reviews?.let { Text(" ($it recensioni)", color = C.Faint, fontSize = 12.sp) }
                    }
                }
                Text(fitText.uppercase(), color = fitCol, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    modifier = Modifier.border(1.dp, fitCol.copy(alpha = .5f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            val where = h.walkVaporettoMin?.let { "$it min a piedi dal vaporetto di San Zaccaria" } ?: palazzoLabel(h.lat, h.lon)
            where?.let { Text(it, color = C.Lido, fontSize = 13.sp) }
            Spacer(Modifier.height(6.dp))
            Text(h.note, color = C.Muted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            // stato live dalla ricerca Google
            val status = when {
                live?.total != null -> "Su Google ora: ${eur(live.total)} per ${s.nights} notti" to priceColor(live.total, s)
                s?.waitingUntil != null -> "Prezzi Google ${dalLabel(runCatching { LocalDate.parse(s.waitingUntil).format(DateTimeFormatter.ofPattern("d MMMM", IT)) }.getOrDefault(""))}" to C.Faint
                live != null -> "Su Google: nessuna tariffa per queste date" to C.Warn
                else -> "Non compare nella ricerca Google: controlla sul sito" to C.Faint
            }
            Text(status.first, color = status.second, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                h.site?.let { url -> LinkButton("Sito", Icons.Rounded.Language, primary = true, small = true) { open(ctx, url) } }
                h.email?.let { mail -> LinkButton("Scrivi", Icons.Rounded.Mail, small = true) { open(ctx, mailDraft(mail, h.name, s)) } }
                LinkButton("Booking", Icons.Rounded.Hotel, small = true) {
                    open(ctx, "https://www.booking.com/searchresults.it.html?ss=${enc(h.name + " Venezia")}&checkin=${s?.checkIn ?: "2027-09-03"}" +
                        "&checkout=${s?.checkOut ?: "2027-09-06"}&group_adults=2&no_rooms=1&group_children=0")
                }
                LinkButton("Mappa", Icons.Rounded.Map, small = true) { open(ctx, "geo:${h.lat},${h.lon}?q=${h.lat},${h.lon}(${enc(h.name)})") }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    checked?.let { "Controllato il " + runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("d MMM", IT)) }.getOrDefault(it) }
                        ?: "Mai controllato",
                    color = if (checked != null) C.Muted else C.Faint, fontSize = 12.sp, modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { markChecked(ctx, h.match); vm.checkedTick++ }) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = C.Gold); Spacer(Modifier.width(4.dp))
                    Text("Controllato oggi", color = C.Gold, fontSize = 13.sp)
                }
            }
        }
    }
}

private fun mailDraft(to: String, hotel: String, s: Snapshot?): String {
    val subject = "Richiesta disponibilità 3–6 settembre 2027 (Mostra del Cinema)"
    val body = """Buongiorno,
        |
        |vorrei soggiornare presso $hotel dal 3 al 6 settembre 2027 (3 notti), 2 adulti, camera matrimoniale.
        |
        |Al momento sul vostro sito quelle date risultano non disponibili: potreste indicarmi quando aprirete le prenotazioni per il periodo della Mostra del Cinema, e se è già possibile fare una richiesta o un'opzione?
        |
        |Grazie mille,
        |Matteo""".trimMargin()
    return "mailto:$to?subject=" + android.net.Uri.encode(subject) + "&body=" + android.net.Uri.encode(body)
}
