# Roma in Tempo Reale (rome_rtgtfs)

App Android open source, scritta in Kotlin, per consultare i passaggi del trasporto pubblico di Roma in tempo reale, usando gli open data GTFS pubblicati da Roma Servizi per la Mobilità.

App non ufficiale, non affiliata a Roma Servizi per la Mobilità né ad Atac.

## Funzioni

- Ricerca delle fermate per nome o codice, e fermate vicine tramite la posizione del telefono.
- Tabellone della fermata per i prossimi 90 minuti: orario previsto in tempo reale, ritardo, fermate mancanti, numero di vettura e affollamento (dato sperimentale). Le corse senza dati in tempo reale mostrano l'orario programmato.
- Avvisi di servizio (deviazioni, lavori, interruzioni), filtrati per le linee della fermata oppure in elenco completo.
- Funziona senza server intermedi: l'app legge direttamente i file pubblicati da Roma Mobilità.

## Dati

Fonte: [Roma Servizi per la Mobilità, Open Data](https://romamobilita.it/sistemi-e-tecnologie/open-data/).
Il dataset "Dati del Trasporto Pubblico (GTFS) del Comune di Roma" è pubblicato sul [portale open data di Roma Capitale](https://dati.comune.roma.it/catalog/dataset/c_h501-d-9000) con licenza **Creative Commons Attribuzione, Condividi allo stesso modo (CC BY-SA)**.

L'app non ridistribuisce i dati: li scarica dalla fonte sul dispositivo dell'utente.

| File | Quando viene scaricato |
|---|---|
| `rome_static_gtfs.zip.md5` | a ogni avvio dell'app e con il pulsante "Controlla aggiornamenti" |
| `rome_static_gtfs.zip` | solo quando l'md5 pubblicato cambia; il file scaricato viene verificato con lo stesso md5 |
| `rome_rtgtfs_trip_updates_feed.pb` e `rome_rtgtfs_vehicle_positions_feed.pb` | a ogni interrogazione di una fermata, al massimo una volta al minuto mentre il tabellone è aperto |
| `rome_rtgtfs_service_alerts_feed.pb` | al massimo ogni 5 minuti |

I feed in tempo reale sono richiesti con `If-Modified-Since`: se il file non è cambiato il server risponde 304 senza corpo.

### Avvertenze della fonte

I dati sono forniti esclusivamente a titolo di supporto al viaggio. Non sono esaustivi del servizio erogato e possono risentire delle condizioni di esercizio su strada e di malfunzionamenti del sistema di localizzazione satellitare dei mezzi (AVM). I dati sull'affollamento sono sperimentali. La possibilità di salire a bordo va sempre verificata al passaggio del mezzo. L'app riporta queste avvertenze nella schermata Informazioni.

## Come funziona

- `StaticUpdateWorker` (WorkManager) confronta l'md5, scarica lo zip e lo importa in un nuovo database SQLite. Il database in uso resta disponibile fino alla fine dell'import.
- `Gtfs.kt` importa il GTFS. Le corse con la stessa sequenza di fermate e gli stessi intervalli condividono uno schema: i circa 5,5 milioni di righe di `stop_times.txt` diventano circa 600 mila, per un database di circa 34 MB.
- `Realtime.kt` scarica i feed GTFS-Realtime e li applica agli orari programmati, secondo la specifica: orario esatto della fermata se presente, altrimenti ritardo propagato dall'ultima fermata aggiornata.
- `MainActivity.kt` contiene l'interfaccia in Jetpack Compose.

## Compilazione

Servono JDK 21 e Android SDK (compileSdk 37).

```sh
./gradlew assembleDebug          # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # test su un estratto reale del feed
```

Test aggiuntivi, eseguiti solo se richiesti:

```sh
# import del feed completo scaricato a mano
GTFS_ZIP=/percorso/rome_static_gtfs.zip ./gradlew testDebugUnitTest
# prova completa contro i server di Roma Mobilità, con screenshot in app/build/screens
E2E=1 ./gradlew testDebugUnitTest --tests '*EndToEnd*'
```

## GitHub Actions

- A ogni push e pull request: test, lint e APK di debug, scaricabile dagli artifact del workflow.
- Avvio manuale (`workflow_dispatch`) e tag `v*`: la release minificata viene provata su un emulatore Android con i dati live; gli screenshot restano negli artifact.
- Tag `v*`: pubblicazione di una GitHub Release con l'APK firmato. Servono i secret `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` e `RELEASE_KEY_PASSWORD`.

## Licenza

Codice sorgente: [MIT](LICENSE). Dati: CC BY-SA, Roma Servizi per la Mobilità e Roma Capitale.
