package it.matteo.veneziahotel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.text.NumberFormat
import java.util.Locale

/* ── Mappa con i prezzi, come su Booking e Airbnb ── */

/**
 * Mappa OpenStreetMap standard (senza chiavi API; CARTO ora la chiede) resa scura con un filtro:
 * colori invertiti e virati verso i toni caldi del tema.
 */
private val DARK_FILTER = ColorMatrixColorFilter(ColorMatrix().apply {
    // inverte e desatura
    set(floatArrayOf(
        -0.30f, -0.59f, -0.11f, 0f, 255f,
        -0.30f, -0.59f, -0.11f, 0f, 255f,
        -0.30f, -0.59f, -0.11f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ))
    // abbassa la luminosità e scalda un po'
    postConcat(ColorMatrix(floatArrayOf(
        0.78f, 0f, 0f, 0f, 8f,
        0f, 0.74f, 0f, 0f, 6f,
        0f, 0f, 0.68f, 0f, 4f,
        0f, 0f, 0f, 1f, 0f,
    )))
})

/** Luoghi della Mostra (coordinate da OpenStreetMap). */
data class Poi(val glyph: String, val name: String, val note: String, val lat: Double, val lon: Double)

val FESTIVAL_POIS = listOf(
    Poi("★", "Palazzo del Cinema", "Sala Grande: anteprime, red carpet e premiazioni. Accanto, la Sala Darsena.", PALAZZO_LAT, PALAZZO_LON),
    Poi("C", "Palazzo del Casinò", "Sale di proiezione (Sala Casinò e altre), sala stampa e accrediti.", 45.406650, 12.367639),
    Poi("E", "Hotel Excelsior", "Dove alloggiano le star e si fanno incontri ed eventi; arrivi in motoscafo dal pontile.", 45.403803, 12.366283),
    Poi("⚓", "Imbarcadero Lido Casinò", "Fermata d'acqua davanti alla Mostra: nei giorni del festival ci fermano le navette dal centro.", 45.407415, 12.367195),
    Poi("V", "Santa Maria Elisabetta", "Vaporetti per San Marco, Ferrovia e Piazzale Roma; in bus al Palazzo in pochi minuti.", 45.418000, 12.368500),
    Poi("F", "San Nicolò · ferry", "Ferry con l'auto per Tronchetto (linea 17).", 45.426176, 12.378485),
)

private fun pinColor(h: Hotel, s: Snapshot): Color = when {
    h.total == null -> C.Muted
    h.total <= s.budgetTarget -> C.Good
    h.total <= s.maxFor(h.zone) -> C.Gold
    else -> C.Bad
}

private fun shortEur(v: Int) = "€" + NumberFormat.getIntegerInstance(Locale.ITALIAN).format(v)

private fun distLabel(m: Int) = if (m < 1000) "$m m" else String.format(Locale.ITALIAN, "%.1f km", m / 1000.0)

/** Etichetta a fumetto con il prezzo (o un testo), disegnata come bitmap per il marker. */
private fun pin(ctx: Context, text: String, bg: Int, fg: Int, outline: Int? = null): BitmapDrawable {
    val d = ctx.resources.displayMetrics.density
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13 * d; typeface = Typeface.DEFAULT_BOLD; color = fg
    }
    val padH = 9 * d; val h = 26 * d; val tail = 6 * d; val stroke = 2 * d
    val w = paint.measureText(text) + padH * 2
    val bmp = Bitmap.createBitmap((w + stroke * 2).toInt(), (h + tail + stroke * 2).toInt(), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val r = RectF(stroke, stroke, stroke + w, stroke + h)
    val shape = Path().apply {
        addRoundRect(r, h / 2, h / 2, Path.Direction.CW)
        moveTo(r.centerX() - tail, r.bottom - 1); lineTo(r.centerX(), r.bottom + tail); lineTo(r.centerX() + tail, r.bottom - 1); close()
    }
    c.drawPath(shape, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = outline ?: 0xCC0F0D0B.toInt(); style = Paint.Style.STROKE; strokeWidth = stroke * 2 })
    c.drawPath(shape, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
    val fm = paint.fontMetrics
    c.drawText(text, r.left + padH, r.centerY() - (fm.ascent + fm.descent) / 2, paint)
    return BitmapDrawable(ctx.resources, bmp)
}

/** Cerchio con una lettera o un simbolo: luoghi della Mostra e hotel senza prezzo. */
private fun dot(ctx: Context, glyph: String?, sizeDp: Float, bg: Int, fg: Int, ring: Int): BitmapDrawable {
    val d = ctx.resources.displayMetrics.density
    val size = sizeDp * d; val ringW = 2 * d
    val bmp = Bitmap.createBitmap((size + ringW * 2).toInt(), (size + ringW * 2).toInt(), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = bmp.width / 2f; val cy = bmp.height / 2f; val r = size / 2
    c.drawCircle(cx, cy, r + ringW / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ring; style = Paint.Style.STROKE; strokeWidth = ringW })
    c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
    if (glyph != null) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * 0.55f; typeface = Typeface.DEFAULT_BOLD; color = fg; textAlign = Paint.Align.CENTER
        }
        val fm = p.fontMetrics
        c.drawText(glyph, cx, cy - (fm.ascent + fm.descent) / 2, p)
    }
    return BitmapDrawable(ctx.resources, bmp)
}

private fun setMarkers(
    map: MapView, ctx: Context, hotels: List<Hotel>, unpriced: List<Hotel>, s: Snapshot,
    onClick: (Hotel) -> Unit, onPoi: (Poi) -> Unit,
) {
    map.overlays.removeAll { it is Marker }
    val dark = C.Bg.toArgb()
    // senza prezzo: puntini grigi, sotto a tutto
    for (h in unpriced) {
        map.overlays.add(Marker(map).apply {
            position = GeoPoint(h.lat!!, h.lon!!)
            icon = dot(ctx, null, 11f, C.Faint.toArgb(), dark, 0xCC0F0D0B.toInt())
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setOnMarkerClickListener { _, _ -> onClick(h); true }
        })
    }
    // i più economici sopra gli altri
    for (h in hotels.sortedByDescending { it.total ?: 0 }) {
        map.overlays.add(Marker(map).apply {
            position = GeoPoint(h.lat!!, h.lon!!)
            // Airbnb con il bordo rosato, come il marchio; Booking e Google con il bordo scuro
            icon = pin(ctx, shortEur(h.total!!), pinColor(h, s).toArgb(), dark,
                if (h.source == "airbnb") 0xFFFF5A5F.toInt() else null)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setOnMarkerClickListener { _, _ -> onClick(h); true }
        })
    }
    // luoghi della Mostra sopra ai prezzi
    for (p in FESTIVAL_POIS.reversed()) {
        val main = p.glyph == "★"
        map.overlays.add(Marker(map).apply {
            position = GeoPoint(p.lat, p.lon)
            icon = if (main) dot(ctx, p.glyph, 30f, C.Text.toArgb(), dark, C.Gold.toArgb())
            else dot(ctx, p.glyph, 24f, C.Surface2.toArgb(), C.Text.toArgb(), C.Text.toArgb())
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setOnMarkerClickListener { _, _ -> onPoi(p); true }
        })
    }
    map.invalidate()
}

@Composable
fun MapTab(vm: MainVm) {
    var listMode by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        Row(
            Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Toggle("Mappa", !listMode) { listMode = false }
            Toggle("Più comodi", listMode) { listMode = true }
        }
        if (listMode) ClosestList(vm) else HotelMap(vm)
    }
}

@Composable
private fun Toggle(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) C.Bg else C.Muted,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) C.Gold else Color.Transparent)
            .border(1.dp, if (selected) C.Gold else C.Line, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun HotelMap(vm: MainVm) {
    val ctx = LocalContext.current
    val s = vm.snap
    var poi by remember { mutableStateOf<Poi?>(null) }
    val visible = vm.visibleHotels().filter { it.available && it.lat != null && it.lon != null }
    val onMap = visible.filter { it.total != null }
    val unpriced = visible.filter { it.total == null }
    val noPos = vm.visibleHotels().count { it.available && it.total != null && (it.lat == null || it.lon == null) }
    val map = remember {
        Configuration.getInstance().apply {
            userAgentValue = ctx.packageName
            osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
            osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
        }
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            overlayManager.tilesOverlay.setColorFilter(DARK_FILTER)
            overlayManager.tilesOverlay.loadingBackgroundColor = C.Bg.toArgb()
            overlayManager.tilesOverlay.loadingLineColor = C.Bg.toArgb()
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 11.0
            maxZoomLevel = 19.0
            controller.setZoom(14.0)
            controller.setCenter(GeoPoint(45.411, 12.360))
            overlays.add(CopyrightOverlay(ctx))
        }
    }
    DisposableEffect(Unit) {
        map.onResume()
        onDispose { map.onPause() }
    }
    if (s != null) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 8.dp)) {
            Filters(vm, s)
            Text(
                "${onMap.size} con prezzo" +
                    (if (unpriced.isNotEmpty()) " · ${unpriced.size} senza prezzo (grigi)" else "") +
                    (if (noPos > 0) " · $noPos senza posizione" else "") +
                    " · i cerchi bianchi sono i luoghi della Mostra",
                style = MaterialTheme.typography.bodySmall, color = C.Faint, modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        AndroidView(
            factory = { map },
            modifier = Modifier.fillMaxSize(),
            update = { m ->
                if (s != null) setMarkers(m, ctx, onMap, unpriced, s, { vm.selected = it }, { poi = it })
            },
        )
        poi?.let { p ->
            Surface(
                color = C.Surface, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, C.Line),
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth().clickable { poi = null },
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(if (p.glyph == "★") C.Text else C.Surface2)
                            .border(2.dp, if (p.glyph == "★") C.Gold else C.Text, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text(p.glyph, color = if (p.glyph == "★") C.Bg else C.Text, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = C.Text, style = MaterialTheme.typography.titleSmall)
                        Text(p.note, color = C.Muted, style = MaterialTheme.typography.bodySmall)
                        distPalazzoM(p.lat, p.lon)?.takeIf { it > 150 }?.let { m ->
                            Text("${distLabel(m)} dal Palazzo · ${walkMin(m)} min a piedi", color = C.Gold,
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    Text("✕", color = C.Faint, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** Tutti gli alloggi disponibili ordinati solo per distanza a piedi dal Palazzo del Cinema: niente filtri di prezzo. */
@Composable
private fun ClosestList(vm: MainVm) {
    val s = vm.snap
    val list = (s?.hotels ?: emptyList())
        .filter { it.available && (vm.showAirbnb || it.source != "airbnb") }
        .mapNotNull { h -> distPalazzoM(h.lat, h.lon)?.let { h to it } }
        .sortedBy { it.second }
    val noPos = (s?.hotels ?: emptyList()).count { it.available && (it.lat == null || it.lon == null) }
    LazyColumn(
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "Solo in base alla posizione: dal più vicino al Palazzo del Cinema, con o senza prezzo." +
                    (if (noPos > 0) " $noPos alloggi non hanno la posizione e non sono in lista." else ""),
                style = MaterialTheme.typography.bodySmall, color = C.Faint,
            )
        }
        if (s == null || list.isEmpty()) item {
            Text("Nessun alloggio con posizione: aggiorna dalla scheda Hotel.", color = C.Muted)
        }
        if (s != null) itemsIndexed(list, key = { _, p -> p.first.id }) { i, (h, m) -> ClosestRow(i + 1, h, m, s) { vm.selected = h } }
    }
}

@Composable
private fun ClosestRow(rank: Int, h: Hotel, m: Int, s: Snapshot, onClick: () -> Unit) {
    Surface(
        color = C.Surface, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, C.Line),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$rank", color = C.Gold, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.width(30.dp))
            Column(Modifier.weight(1f)) {
                Text(h.name, color = C.Text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val where = if (h.zone == "lido") "${walkMin(m)} min a piedi · ${distLabel(m)}"
                else "centro storico · ${distLabel(m)} in linea d'aria, serve il vaporetto"
                Text(where, color = if (h.zone == "lido") C.Lido else C.Centro, style = MaterialTheme.typography.bodySmall)
                val extra = listOfNotNull(sourceName(h.source) ?: "Google", refundInfo(h)?.first).joinToString(" · ")
                Text(extra, color = C.Faint, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                h.total?.let { shortEur(it) } ?: "senza prezzo",
                color = pinColor(h, s),
                fontWeight = if (h.total != null) FontWeight.Bold else FontWeight.Normal,
                fontSize = if (h.total != null) 15.sp else 12.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
