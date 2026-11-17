package it.matteo.veneziahotel

import android.content.Context
import org.json.JSONObject
import java.net.URLEncoder

/*
 * Airbnb per il Lido, letto dal telefono come Booking.
 *
 * La ricerca per nome ("Lido di Venezia") restituisce tutta Venezia, quindi si cerca per
 * rettangoli di mappa (nord e sud dell'isola) e poi si tengono solo gli annunci le cui
 * coordinate cadono davvero al Lido. I dati stanno nel JSON che Airbnb mette nella pagina
 * (script "data-deferred-state-0"): prezzo totale, coordinate, "Cancellazione gratuita".
 */

/** Zona dalle coordinate, come classify_zone nello script Python. */
fun classifyZone(lat: Double?, lon: Double?): String? {
    if (lat == null || lon == null) return null
    if (lat in 45.425..45.450 && lon in 12.300..12.372) return "centro"
    if (lat >= 45.33 && lat < 45.40 && lon >= 12.32) return "lido"
    if (lat in 45.40..45.437 && lon >= 12.35) return "lido"
    return null
}

/** Rettangoli (sud-ovest, nord-est) che coprono il Lido: nord (Santa Maria Elisabetta, Palazzo) e sud (Malamocco, Alberoni). */
private val LIDO_BOXES = listOf(
    doubleArrayOf(45.395, 12.350, 45.432, 12.398),
    doubleArrayOf(45.335, 12.315, 45.400, 12.365),
)

private fun airbnbSearchUrl(checkIn: String, checkOut: String, adults: Int, box: DoubleArray, cursor: String?) =
    "https://www.airbnb.it/s/Lido-di-Venezia--Venezia/homes?checkin=$checkIn&checkout=$checkOut&adults=$adults" +
        "&currency=EUR&search_by_map=true&search_type=user_map_move&zoom_level=14" +
        "&sw_lat=${box[0]}&sw_lng=${box[1]}&ne_lat=${box[2]}&ne_lng=${box[3]}" +
        (cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") } ?: "")

private const val AIRBNB_JS = """(function(){
var el=document.getElementById('data-deferred-state-0');
if(!el){var t=document.body?document.body.innerText:'';
 return JSON.stringify({state:/nessun risultato|nessuna corrispondenza|nessun alloggio/i.test(t)?'empty':'wait'});}
var root;try{root=JSON.parse(el.textContent);}catch(e){return JSON.stringify({state:'wait'});}
var res=null;(function walk(o){if(res||!o||typeof o!=='object')return;if(o.searchResults&&o.paginationInfo){res=o;return;}for(var k in o)walk(o[k]);})(root);
if(!res)return JSON.stringify({state:'wait'});
var items=res.searchResults.filter(function(it){return it.demandStayListing;}).map(function(it){
 var d=it.demandStayListing,c=(d.location||{}).coordinate||{},pl=(it.structuredDisplayPrice||{}).primaryLine||{};
 var nm=((d.description||{}).name||{}).localizedStringWithTranslationPreference;var pic=(it.contextualPictures||[])[0];
 var id='';try{id=atob(d.id).split(':').pop();}catch(e){}
 return {id:id,name:nm||it.title||'',kind:it.title||'',price:pl.discountedPrice||pl.price||null,label:pl.accessibilityLabel||null,
  fc:(it.paymentMessages||[]).some(function(m){return m.type==='FREE_CANCELLATION_HIGHLIGHT';}),
  rating:it.avgRatingLocalized||null,lat:c.latitude,lon:c.longitude,img:pic&&pic.picture||null};});
return JSON.stringify({state:'ok',n:items.length,next:(res.paginationInfo||{}).nextPageCursor||null,items:items});
})()"""

private fun firstNumber(text: String?): Int? =
    text?.let { Regex("\\d[\\d.]*").find(it) }?.value?.replace(".", "")?.toIntOrNull()

fun parseAirbnb(o: JSONObject, checkIn: String, checkOut: String, adults: Int): List<BookingOffer> {
    val items = o.optJSONArray("items") ?: return emptyList()
    return (0 until items.length()).mapNotNull { i ->
        val it = items.getJSONObject(i)
        val id = it.optString("id").ifBlank { return@mapNotNull null }
        val lat = if (it.has("lat")) it.optDouble("lat") else null
        val lon = if (it.has("lon")) it.optDouble("lon") else null
        if (classifyZone(lat, lon) != "lido") return@mapNotNull null
        // senza prezzo = già occupato per quelle date
        val total = firstNumber(it.optString("price").ifBlank { null }) ?: firstNumber(it.optString("label").ifBlank { null })
            ?: return@mapNotNull null
        val rating = it.optString("rating")
        val fc = it.optBoolean("fc")
        BookingOffer(
            id = "airbnb-$id",
            name = it.optString("name").replace(Regex("\\s+"), " ").trim().ifEmpty { "Alloggio Airbnb" },
            total = total,
            link = "https://www.airbnb.it/rooms/$id?check_in=$checkIn&check_out=$checkOut&adults=$adults",
            image = it.optString("img").ifBlank { null }?.takeIf { u -> u.startsWith("http") },
            // Airbnb vota su 5: lo riportiamo su 10 come Booking (il merge lo dimezza di nuovo)
            score10 = Regex("(\\d),(\\d+)").find(rating)?.let { m -> "${m.groupValues[1]}.${m.groupValues[2]}".toDoubleOrNull() }?.times(2),
            reviews = Regex("\\((\\d+)\\)").find(rating)?.groupValues?.get(1)?.toIntOrNull(),
            stars = null,
            dist = null,
            zone = "lido",
            // senza la scritta non è detto che sia non rimborsabile (Airbnb ha anche rimborsi parziali)
            refundable = if (fc) true else null,
            refundableTotal = if (fc) total else null,
            source = "airbnb",
            // "Lido: stanza ⋅ ..." → "Stanza"
            kind = it.optString("kind").substringBefore("⋅").substringAfter(":").trim()
                .replaceFirstChar { c -> c.uppercase() }.ifEmpty { null },
            lat = lat, lon = lon,
        )
    }
}

/** Annunci Airbnb disponibili al Lido per il soggiorno, da tutte le pagine dei due rettangoli. */
suspend fun fetchAirbnbLido(ctx: Context, checkIn: String, checkOut: String, adults: Int): List<BookingOffer> {
    val out = mutableListOf<BookingOffer>()
    for (box in LIDO_BOXES) {
        var cursor: String? = null
        for (page in 0 until 5) {
            val o = readPageJson(ctx, airbnbSearchUrl(checkIn, checkOut, adults, box, cursor), AIRBNB_JS, "Airbnb")
            if (o.optString("state") != "ok") break
            out += parseAirbnb(o, checkIn, checkOut, adults)
            cursor = o.optString("next").ifBlank { null }?.takeIf { it != "null" } ?: break
        }
    }
    return out.distinctBy { it.id }
}
