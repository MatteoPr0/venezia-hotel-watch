# Venezia Hotel Watch 🎬

App Android che tiene d'occhio gli hotel per la **Mostra del Cinema di Venezia 2027**
(ven 3 → lun 6 settembre, 2 adulti, budget ideale 1.000 €, massimo 1.200 €).

## Come funziona

```
GitHub Actions (2×/giorno) ──► SerpApi · Google Hotels ──► data/hotels.json (nel repo)
                                                              │
                         App Android (ogni ~6 h, WorkManager) ◄┘ ──► notifica novità
```

- **Zone**: Lido (priorità, comodo per la Mostra) + centro storico (Castello/San Marco, ~15 min di vaporetto).
  Mestre e terraferma sono esclusi automaticamente dalle coordinate GPS.
- **Notifiche**: nuovo hotel, prezzo sceso di almeno 25 €, camera tornata disponibile.
  Un hotel viene segnato "esaurito" dopo 2 controlli consecutivi in cui non compare.
- **Consumo**: 3 ricerche SerpApi a controllo → ~186 al mese (il piano gratuito ne dà 250).

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
