# Milestone 05: stato

Esperienza in gioco: Bloom Menu, framework Bloom Mods, HUD Studio, World
Tracker, Inventory Manager. Minecraft 26.3, Fabric Loader 0.20.1, Java 25,
senza Fabric API. Guida tecnica: [BLOOM_MODS.md](BLOOM_MODS.md).

Verifica eseguita: compilazione completa e avvio del client di sviluppo
(mixin applicati, ingresso e uscita da un mondo, apertura di menu, pagine
delle mod, impostazioni e HUD Studio, HUD in gioco) con screenshot
automatici. Le prove di gioco approfondite (interazioni, multiplayer,
shader, mod di terze parti) restano al proprietario del progetto, come
richiesto.

## Implementato

| Fase | Cosa |
|---|---|
| Bloom Menu | Right Shift (rimappabile in Controlli, categoria "Bloom Client"), anche sopra schermata iniziale e menu di pausa; Esc e Right Shift chiudono (non mentre si scrive); animazioni di apertura, chiusura e cambio sezione (disattivabili); canvas a scala intera indipendente dalla GUI scale (Piccolo/Normale/Grande); sidebar con logo, ricerca, Home, Mods, HUD Studio, Settings; Home con versione Minecraft, Bloom, stato del motore, backend e GPU, loader, Java, memoria, mondo, mod; ricerca su mod, pagine, impostazioni (con evidenziazione) e widget; Ctrl+F |
| Bloom Mods | `BloomMod` (onInitialize/onEnable/onDisable/onShutdown), `ModContext` (impostazioni, eventi, task, widget, pagine, storage, dati di gioco, errori, notifiche), `ModMetadata` (id, nome, descrizione, versione, autore, categoria, icona); pagina Mods con ricerca, filtri per categoria e stato, schede con interruttore, Apri, Impostazioni, Info; dettaglio con pagine della mod, impostazioni generate, info ed errori; stato persistente; isolamento errori (init fallito, enable fallito, 3 errori/60 s) |
| HUD Studio | Anteprima live con valori reali; drag and drop; ridimensionamento dalla maniglia; posizione precisa (X/Y, frecce, Shift ×10); 9 ancoraggi; allineamento allo schermo; griglia (passo configurabile) e guide verso centro e altri elementi (Alt = libero); livelli (avanti/indietro, visibilità); duplicazione; eliminazione; ripristino posizione; colori testo/etichetta/sfondo/bordo con selettore (tavolozza, HSV, alfa, hex); opacità; dimensione testo; font Predefinito/Uniforme; padding; sfondo; bordo e spessore; angoli; spaziatura; ombra; allineamento; opzioni per widget; copia/incolla stile; isola selezione (H); pannelli nascondibili (Tab); undo/redo; esporta (file + appunti) e importa (appunti o file); ripristino layout |
| Sistema HUD | Registro widget con proprietario, layout versionato con migrazioni, ancoraggi per ogni risoluzione, aggiornamento dati separato dal disegno, misure in cache, HUD visibile durante il gioco (nascosto con F1, opzionalmente con F3), voce F3 `bloom:client` con tempo per frame; widget FPS, Coordinate, Bioma, Direzione, Memoria, Ping (solo multiplayer), Stato armatura |
| World Tracker | Punti con nome, descrizione, categoria (Casa, Base, Villaggio, Struttura, Risorsa, Portale, Personalizzato), dimensione, coordinate, data; modifica, eliminazione con conferma, ricerca, filtro, ordinamento per distanza/nome/data, copia coordinate, punto seguito sull'HUD, conversione Overworld/Nether 1:8; teletrasporto solo in singleplayer con comandi consentiti; esplorazione: chunk visitati per dimensione (raggio configurabile), storico posizioni, ultime posizioni, percorsi recenti, tempo e distanza per dimensione, biomi visitati (su totale del registro); suggerimenti mai salvati da soli (villaggio da ≥3 villici visibili, entrambe le estremità di un portale del Nether appena usato, ultima morte, spawn del mondo) con Salva/Ignora; mappa dei chunk esplorati con traccia, punti, zoom e trascinamento; widget Dimensione, Distanza dal luogo, Coordinate collegate, Panoramica (righe a scelta); dati per mondo/server |
| Inventory Manager | Analisi: conteggi, oggetti in più stack, stack unibili, categorie da componenti e tag (strumenti, armi, armatura, cibo, consumabili, blocchi, materiali), ricerca, ordinamenti, durabilità bassa (soglia), cibo (punti fame), frecce, spazio libero e negli stack; organizzazione: regole per ogni slot della hotbar (tipo di strumento, cibo, blocchi o oggetto specifico), suggerimenti per hotbar, armatura migliore (punti armatura reali), cibo, stack da unire, inventario quasi pieno, evidenziati nell'inventario vanilla; risorse: in esaurimento (minimi per oggetto), importanti (stella), più ingombranti, materiali; obiettivi per mondo con requisiti manuali o da ricette del ricettario del giocatore (quantità desiderata → numero di craft), progresso reale; widget Riepilogo, Oggetto selezionato, Durabilità, Slot liberi, Progresso obiettivo |
| Integrazione | Traduzioni inglese e italiano (505 chiavi) servite senza Fabric API; impostazioni Bloom (animazioni, dimensione menu, sfocatura, apertura da menu, HUD, griglia, guide); tasti, motore, cartelle dati, ripristini nella pagina Settings; salvataggi atomici asincroni con flush all'uscita |

## Scelte e limiti

| Punto | Scelta |
|---|---|
| Dove gira | Nello stesso jar del motore (`bloom-engine-0.3.0.jar`): il launcher lo aggiunge già alle istanze "Bloom Native · Rust". Le istanze "Java · Renderer vanilla" non ricevono il jar e quindi non hanno menu e HUD Bloom |
| Spostamento automatico degli oggetti | Non implementato di proposito: la specifica vieta click o pacchetti simulati; i suggerimenti sono evidenziati nell'inventario |
| Ricette | Solo quelle presenti nel ricettario del giocatore (il client 26.3 non riceve l'elenco completo delle ricette dal server); nessun requisito inventato |
| Punti d'interesse "vicini" | Solo da dati già visibili al client (entità renderizzate, blocco portale sotto il giocatore, ultima morte, spawn); nessuna scansione dei chunk |
| Font HUD | Predefinito e Uniforme di Minecraft; nessun font esterno incluso |
| API pubblica per mod di terzi | L'API è separata e documentata, ma le mod vengono registrate nel codice di Bloom; non c'è caricamento di mod Bloom esterne né marketplace |
| Rust | Non usato in questa milestone (nessun calcolo che ne tragga vantaggio) |
| Voce F3 `bloom:client` | Registrata ma spenta di default: si attiva da Opzioni di debug |
| Test | Nessuna suite automatica aggiunta, come richiesto |

## File

- Codice: `java/src/main/java/com/bloomclient/client/` (api, core, hud, ui, mixin, mods)
- Traduzioni: `java/src/main/resources/assets/bloom/lang/{en_us,it_it}.json`
- Mixin: `java/src/main/resources/bloom-client.mixins.json`
- Dati del giocatore: `config/bloom/` (impostazioni, `mods.json`, `hud/layout.json`, `hud/exports/`, `mods/<id>.json`), `.bloom/worlds/<mondo>/<mod>/`, `.bloom/mods/<mod>/`
