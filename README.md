# Venezia Hotel Watch 🎬

App Android che tiene d'occhio gli hotel per la **Mostra del Cinema di Venezia 2027**
(2 adulti, budget ideale 1.000 €, massimo 1.200 € in centro e 1.500 € al Lido;
la Mostra 2027 è annunciata dal 1° all'11 settembre). Si controllano due weekend:
**gio 2 → dom 5** (apertura) e **gio 9 → dom 12** (chiusura, premiazione sabato 11): in alto nell'app si sceglie quale vedere.

## Come funziona

```
GitHub Actions (2×/giorno) ──► SerpApi · Google Hotels ──► data/hotels.json (nel repo)
                                                              │
                         App Android (ogni ~6 h, WorkManager) ◄┘ ──► notifica novità
```

- **Zone**: Lido (priorità, comodo per la Mostra) + centro storico (Castello/San Marco, ~15 min di vaporetto).
  Mestre e terraferma sono esclusi automaticamente dalle coordinate GPS.
- **Booking dal telefono**: Google Hotels per la Mostra non ha tariffe per quasi nessun hotel del Lido.
  L'app apre quindi Booking in una WebView invisibile (Booking blocca le richieste dai server)
  e unisce i risultati del Lido, e del centro storico entro budget, a quelli di Google.
- **Rimborsabilità**: da Booking (scritta "Cancellazione gratuita" + ricerca con quel filtro) e da
  Google (una ricerca al giorno con `free_cancellation=true` per il centro).
- **Notifiche**: nuovo hotel, prezzo sceso di almeno 25 €, camera tornata disponibile.
  Un hotel viene segnato "esaurito" dopo 2 controlli consecutivi in cui non compare.
- **Secondo weekend**: in `config.json` → `alt_stays`. Su Google solo il Lido, una ricerca al giorno, salvata in
  `data/hotels-2.json`; Booking, Airbnb, Holidu e Trip.com li legge il telefono per entrambi i weekend.
  Le notifiche dicono a quale weekend si riferiscono.
- **Consumo**: 3 ricerche SerpApi a controllo (2 al giorno) + 1 ogni 2 giorni per i rimborsabili + 1 al giorno per il
  secondo weekend → ~233 al mese (il piano gratuito ne dà 250).

## Setup (una volta sola, ~10 minuti)

1. **SerpApi**: registrati gratis su https://serpapi.com/users/sign_up e copia la tua API key
   da https://serpapi.com/manage-api-key
2. **GitHub**: crea un repository **pubblico** chiamato `venezia-hotel-watch` e carica
   il contenuto di questa cartella. Dal Mac il modo più semplice è `git push` (o GitHub Desktop);
   se usi *Add file → Upload files* dal browser, premi ⌘⇧. nel Finder per vedere la cartella
   nascosta `.github` e trascinala insieme al resto, altrimenti i workflow non vengono caricati.
   Pubblico serve perché l'app legga `hotels.json` senza token; la API key resta segreta.
3. **Secret**: Settings → Secrets and variables → Actions → *New repository secret*
   - `SERPAPI_KEY` = la tua chiave
   - (facoltativo) `NTFY_TOPIC` = un nome a caso tipo `venezia27-matteo-x8k2`, se vuoi anche le
     push dell'app [ntfy](https://ntfy.sh) come riserva
4. **Permessi Actions**: Settings → Actions → General → *Workflow permissions* → **Read and write**.
5. **Primo controllo**: tab Actions → "Controllo hotel" → *Run workflow*.
6. **APK**: il workflow "Build APK" parte da solo al primo push; trovi `VeneziaHotelWatch.apk`
   nella sezione **Releases** del repo. L'APK compilato da GitHub ha già l'indirizzo giusto dei dati.

## Cambiare date o budget

Modifica `config.json` direttamente da GitHub (icona matita). Quando la Biennale annuncerà le
date ufficiali 2027, basta aggiornare `check_in` / `check_out`.

## Note

- I prezzi sono il totale del soggiorno per 2 persone come mostrato da Google Hotels;
  la tassa di soggiorno di Venezia è esclusa.
- Molti hotel pubblicano le tariffe per settembre 2027 solo 10–12 mesi prima: nelle prime
  settimane è normale vedere pochi risultati.
- Su OnePlus, se le notifiche non arrivano: Impostazioni dell'app → *Ottimizzazione batteria* → Non ottimizzare.
- Keystore di firma fisso in `android/app/release.jks` (app personale): ogni nuova build si installa sopra la precedente.
