package it.matteo.veneziahotel

import android.content.Context
import org.json.JSONObject

/*
 * Altri siti letti dal telefono, come Booking e Airbnb.
 *
 * Holidu: raccoglie case e camere da Vrbo, Booking e altri; i dati (prezzo totale, coordinate,
 *   cancellazione gratuita, sito d'origine) stanno nel JSON che la pagina porta con sé.
 *   Vrbo, Expedia e Hotels.com da soli dopo poche letture mostrano la verifica anti-robot.
 * Trip.com: filtro "vicino al Lido" ordinato per distanza; la pagina contiene i primi 10
 *   risultati con coordinate e "Prezzo totale". Le pagine successive arrivano solo scorrendo.
 */

private fun holiduUrl(checkIn: String, checkOut: String, adults: Int) =
    "https://www.holidu.it/s/Lido-di-Venezia--Riviera-Adriatica--Italia?checkin=$checkIn&checkout=$checkOut&adults=$adults"

private const val HOLIDU_JS = """(function(){
if(document.readyState==='loading')return JSON.stringify({state:'wait'});
var s=[].slice.call(document.scripts).find(function(s){return s.type==='application/json'&&s.textContent.indexOf('"redux"')>=0;});
if(!s){var t=document.body?document.body.innerText:'';return JSON.stringify({state:/mostraci il tuo lato umano|sei un robot/i.test(t)?'blocked':'wait'});}
var o;try{o=JSON.parse(s.textContent.trim().replace(/^<!--/,'').replace(/-->$/,''));}catch(e){return JSON.stringify({state:'wait'});}
var offers=[];(function w(x){if(!x||typeof x!=='object')return;
 if(x.offers&&typeof x.offers==='object'&&!Array.isArray(x.offers)){var v=Object.keys(x.offers).map(function(k){return x.offers[k];});
  if(v.length&&v[0]&&v[0].price&&v[0].location)offers=offers.concat(v);}
 for(var k in x)if(k!=='offers')w(x[k]);})(o);
var items=offers.filter(function(f){return f.isAvailable!==false&&f.price&&f.price.total;}).map(function(f){
 var d=f.details||{},l=f.location||{},ph=(f.photos||[])[0]||{},r=f.rating||{};
 return {id:f.id,name:(d.name||'').replace(/<[^>]+>/g,' '),longName:d.longName||'',kind:d.apartmentTypeName||null,total:f.price.total,
  lat:l.lat,lon:l.lng,prov:(f.provider||{}).legalName||null,link:f.internalLink||null,img:ph.m||ph.l||null,
  rating:r.value||null,reviews:r.count||null,fc:!!(f.cancellationPolicy&&f.cancellationPolicy.freeCancellation)};});
return JSON.stringify({state:'ok',n:items.length,items:items});
})()"""

/** "Booking.com B.V." → "Booking" */
private fun providerName(p: String?): String? = p?.substringBefore(".com")?.substringBefore(" ")?.trim()?.ifEmpty { null }

fun parseHolidu(o: JSONObject): List<BookingOffer> {
    val items = o.optJSONArray("items") ?: return emptyList()
    return (0 until items.length()).mapNotNull { i ->
        val it = items.getJSONObject(i)
        val lat = if (it.has("lat")) it.optDouble("lat") else null
        val lon = if (it.has("lon")) it.optDouble("lon") else null
        if (classifyZone(lat, lon) != "lido") return@mapNotNull null
        val total = it.optInt("total").takeIf { t -> t > 0 } ?: return@mapNotNull null
        val kind = it.optString("kind").ifBlank { null }
        val isHotel = kind.equals("Hotel", true)
        val name = it.optString("name").replace(Regex("\\s+"), " ").trim().trimEnd('.', ' ')
            .ifEmpty { it.optString("longName") }.ifEmpty { "Alloggio Holidu" }
        val fc = it.optBoolean("fc")
        BookingOffer(
            // gli hotel con lo stesso nome di Google/Booking si uniscono a loro; le case restano a sé
            id = if (isHotel) hotelSlug(name) else "holidu-" + it.optString("id"),
            name = name,
            total = total,
            link = it.optString("link").ifBlank { null }?.let { l -> if (l.startsWith("http")) l else "https://www.holidu.it$l" },
            image = it.optString("img").ifBlank { null }?.takeIf { u -> u.startsWith("http") },
            score10 = if (it.has("rating") && !it.isNull("rating")) it.optDouble("rating") / 10 else null,
            reviews = if (it.has("reviews") && !it.isNull("reviews")) it.optInt("reviews") else null,
            stars = null,
            dist = providerName(it.optString("prov").ifBlank { null })?.let { p -> "Su Holidu da $p" },
            zone = "lido",
            refundable = if (fc) true else null,
            refundableTotal = if (fc) total else null,
            source = "holidu",
            kind = kind?.takeIf { !isHotel },
            lat = lat, lon = lon,
        )
    }.distinctBy { it.id }
}

suspend fun fetchHoliduLido(ctx: Context, checkIn: String, checkOut: String, adults: Int): List<BookingOffer> {
    val o = readPageJson(ctx, holiduUrl(checkIn, checkOut, adults), HOLIDU_JS, "Holidu")
    if (o.optString("state") == "blocked") error("Holidu chiede la verifica anti-robot")
    return parseHolidu(o)
}

/* ── Trip.com ── */

private fun tripUrl(checkIn: String, checkOut: String, adults: Int) =
    "https://it.trip.com/hotels/list?city=688&checkin=${checkIn.replace('-', '/')}&checkout=${checkOut.replace('-', '/')}" +
        "&adult=$adults&crn=1&searchType=CT&curr=EUR" +
        // 8~9778 = vicino a "Venice-Lido", 17~9 = ordinati per distanza
        "&listFilters=8~9778%2A8%2A9778%2C17~9%2A17%2A9"

private const val TRIP_JS = """(function(){
if(document.readyState==='loading')return JSON.stringify({state:'wait'});
var sc=[].slice.call(document.scripts).map(function(s){return s.textContent;}).find(function(x){return x.indexOf('initListData')>=0;});
if(!sc){var b=document.body?document.body.innerText:'';return JSON.stringify({state:/verifica|captcha|robot/i.test(b)&&b.length<2000?'blocked':'wait'});}
var t;try{t=JSON.parse(sc.slice(sc.indexOf('push([1,')+8,sc.lastIndexOf('])')));}catch(e){return JSON.stringify({state:'wait'});}
var k='"initListData":',i=t.indexOf(k);if(i<0)return JSON.stringify({state:'wait'});
var j=i+k.length,d=0,s=j;for(;j<t.length;j++){var ch=t[j];if(ch==='"'){j++;while(t[j]!=='"'){if(t[j]==='\\')j++;j++;}continue;}
 if(ch==='{')d++;else if(ch==='}'){d--;if(!d){j++;break;}}}
var o;try{o=JSON.parse(t.slice(s,j));}catch(e){return JSON.stringify({state:'wait'});}
var items=(o.hotelList||[]).map(function(h){var hi=h.hotelInfo||{},r=(h.roomInfo||[])[0]||{},p=r.priceInfo||{},
 c=((hi.positionInfo||{}).mapCoordinate||[])[0]||{},cm=hi.commentInfo||{};
 return {id:(hi.summary||{}).hotelId,name:(hi.nameInfo||{}).name,price:p.priceExplanation||null,lat:c.latitude,lon:c.longitude,
  img:(hi.hotelImages||{}).url||null,stars:(hi.hotelStar||{}).star||null,score:cm.commentScore||null,reviews:cm.commenterNumber||null,
  fc:JSON.stringify(r.roomTags||'').indexOf('Cancellazione gratuita')>=0};});
return JSON.stringify({state:'ok',n:items.length,items:items});
})()"""

fun parseTrip(o: JSONObject, checkIn: String, checkOut: String, adults: Int): List<BookingOffer> {
    val items = o.optJSONArray("items") ?: return emptyList()
    return (0 until items.length()).mapNotNull { i ->
        val it = items.getJSONObject(i)
        val lat = it.optString("lat").toDoubleOrNull()
        val lon = it.optString("lon").toDoubleOrNull()
        val zone = classifyZone(lat, lon) ?: return@mapNotNull null
        // "Prezzo totale: 3.022 €\n1 camera × 3 notti…" (senza = nessuna camera libera)
        val total = Regex("totale:\\s*([\\d.]+)").find(it.optString("price"))?.groupValues?.get(1)
            ?.replace(".", "")?.toIntOrNull() ?: return@mapNotNull null
        val name = it.optString("name").trim().ifEmpty { return@mapNotNull null }
        val id = it.optString("id")
        val fc = it.optBoolean("fc")
        BookingOffer(
            id = hotelSlug(name), name = name, total = total,
            link = "https://it.trip.com/hotels/detail/?hotelId=$id&checkIn=$checkIn&checkOut=$checkOut&adult=$adults&crn=1&curr=EUR",
            image = it.optString("img").ifBlank { null }?.takeIf { u -> u.startsWith("http") },
            score10 = it.optString("score").replace(',', '.').toDoubleOrNull(),
            reviews = Regex("\\d+").find(it.optString("reviews").replace(".", ""))?.value?.toIntOrNull(),
            stars = it.optInt("stars", 0).takeIf { s -> s in 1..5 },
            dist = null, zone = zone,
            refundable = fc,
            refundableTotal = if (fc) total else null,
            source = "trip",
            lat = lat, lon = lon,
        )
    }.distinctBy { it.id }
}

suspend fun fetchTripLido(ctx: Context, checkIn: String, checkOut: String, adults: Int): List<BookingOffer> {
    val o = readPageJson(ctx, tripUrl(checkIn, checkOut, adults), TRIP_JS, "Trip.com")
    if (o.optString("state") == "blocked") error("Trip.com chiede la verifica anti-robot")
    return parseTrip(o, checkIn, checkOut, adults)
}
