# Bloom Mods: architettura e guida per sviluppatori

Questa guida descrive la parte "in gioco" di Bloom Client introdotta con la
Milestone 05: Bloom Menu, HUD Studio, framework Bloom Mods e le mod
ufficiali (World Tracker, Inventory Manager, Memoria della base). Tutti gli esempi compilano
contro il codice attuale (l'esempio completo è stato compilato nel progetto
prima di essere copiato qui).

## 1. Dove vive il codice

Bloom Client in gioco sta nello stesso jar del motore (`bloom-engine-<versione>.jar`,
mod Fabric `bloom-engine`), nel package `com.bloomclient.client`, con un
proprio entrypoint (`BloomClient`) e un proprio file mixin
(`bloom-client.mixins.json`). È indipendente dal motore di rendering:
funziona allo stesso modo con il mesher nativo attivo, spento o fermato per
un errore.

| Componente | Classe/package | Ruolo |
|---|---|---|
| Bootstrap | `BloomClient` | Crea impostazioni, HUD, gestore mod; tick, shutdown, voce F3 |
| Bloom Menu | `ui.screen.BloomMenuScreen` + `*Page` | Menu Right Shift: Home, Mods, HUD Studio, Settings, Search |
| UI toolkit | `ui.Ui`, `Draw`, `Icon`, `BloomTheme`, `TextInput`, `SettingsView` | Controlli condivisi da menu, Studio e pagine delle mod |
| Schermi Bloom | `ui.BloomScreen` | Canvas a scala intera indipendente dalla GUI scale, animazioni, toast, conferme |
| Bloom Mods API | `api.*` | `BloomMod`, `ModContext`, `ModMetadata`, `ModPage`, eventi, impostazioni, storage, HUD |
| Gestore mod | `core.ModManager`, `core.ModContainer` | Registro, lifecycle, isolamento errori, stato persistente |
| Eventi | `core.EventBus`, `api.event.*` | Tick, ingresso/uscita mondo, cambio dimensione, inventario cambiato |
| Configurazioni | `api.settings.SettingsGroup`, `Setting` | Impostazioni tipizzate, persistenti, renderizzate e ricercabili in automatico |
| Persistenza | `core.BloomStorage`, `api.storage.*` | Scritture atomiche asincrone, dati per mondo/server |
| Identità mondo | `core.WorldSession`, `api.storage.WorldIdentity` | Rileva ingresso/uscita/dimensione e identifica il mondo |
| Dati di gioco | `api.GameInfo` | Posizione, dimensione, bioma, FPS, ping, permessi: aggiornati una volta per tick |
| HUD | `hud.HudManager`, `hud.HudRenderer`, `hud.widgets.CoreWidgets` | Registro widget, layout versionato, aggiornamento dati, rendering |
| HUD Studio | `ui.screen.HudStudioScreen` | Editor visuale dell'HUD |
| World Tracker | `mods.worldtracker.*` | Mod ufficiale `bloom-world-tracker` |
| Inventory Manager | `mods.inventory.*` | Mod ufficiale `bloom-inventory-manager` |
| Memoria della base | `mods.basememory.*` | Mod ufficiale `bloom-base-memory` (vedi §13) |

### Agganci al gioco (mixin)

Bloom non richiede Fabric API, quindi gli agganci sono mixin propri, tutti
minimi e passanti:

| Mixin | Punto | Scopo |
|---|---|---|
| `MinecraftClientMixin` | `Minecraft.tick` (fine), `stop`, `close` | Tick di Bloom, shutdown |
| `GuiMixin` | `Gui.handleKeybinds` (fine) | Tasti Bloom in gioco (vanilla lo chiama solo senza schermate aperte) |
| `KeyboardHandlerMixin` | prima di `Screen.keyPressed` | Apre il menu sopra schermata iniziale e menu di pausa |
| `OptionsMixin` | `Options.load` (inizio) | Aggiunge i `KeyMapping` Bloom alla lista vanilla (rimappabili in Controlli) |
| `HudMixin` | `Hud.extractRenderState`, prima della chat | Passata HUD di Bloom |
| `ClientLanguageMixin` | `ClientLanguage.getOrDefault/has` | Traduzioni Bloom (vedi §9) |
| `InventoryScreenOverlayMixin` | `AbstractContainerScreen.extractContents` | Evidenziazioni dell'Inventory Manager nell'inventario e dell'oggetto cercato dalla Memoria della base in ogni contenitore |

Rust non è coinvolto: niente in questa milestone è un calcolo pesante e una
chiamata JNI per leggere un valore di gioco costerebbe più del valore stesso.

## 2. Lifecycle di una mod

```
avvio ─► onInitialize (sempre, una volta) ─► impostazioni caricate
       └► se attiva: onEnable
giocatore spegne / troppi errori ─► onDisable  (listener e task di onEnable rimossi)
giocatore riaccende            ─► onEnable
chiusura del gioco             ─► onDisable (se attiva) ─► onShutdown
```

- `onInitialize(ModContext)`: crea impostazioni, registra widget HUD e
  pagine. Viene chiamato anche se la mod è spenta.
- `onEnable(ModContext)`: registra listener (`on`) e task (`every`); Bloom li
  rimuove da solo alla disattivazione.
- `onDisable(ModContext)`: ferma il lavoro proprio e salva.
- `onShutdown(ModContext)`: ultima occasione per salvare.

Tutte le chiamate avvengono sul thread client. Le mod ufficiali sono
compilate dentro Bloom e registrate in `BloomClient.onInitializeClient`:

```java
mods.register(new WorldTrackerMod());
mods.register(new InventoryManagerMod());
mods.register(new BaseMemoryMod());
```

Bloom non scarica né esegue codice esterno; non esiste un marketplace. Lo
stato attiva/disattiva è salvato in `config/bloom/mods.json`.

### Gestione degli errori

| Dove | Effetto |
|---|---|
| `onInitialize` | La mod resta spenta per la sessione (stato `FAILED`), il resto funziona |
| `onEnable` | La mod viene spenta (`onDisable` chiamato per ripulire) |
| listener, task, widget, pagine | L'errore viene registrato; 3 errori in 60 s spengono la mod e un messaggio compare nella action bar |

Gli errori sono visibili nella scheda **Info** della mod (ora, punto, messaggio)
e nel log (`bloom/<id>`). La scelta del giocatore viene mantenuta: una mod
spenta per errori riparte alla sessione successiva.

## 3. Creare una mod

Esempio completo: tempo di sessione con impostazioni, eventi, dati per mondo,
widget HUD e pagina nel menu.

```java
package com.example.bloom;

import com.bloomclient.client.api.BloomMod;
import com.bloomclient.client.api.GameInfo;
import com.bloomclient.client.api.ModCategory;
import com.bloomclient.client.api.ModContext;
import com.bloomclient.client.api.ModMetadata;
import com.bloomclient.client.api.ModPage;
import com.bloomclient.client.api.event.DimensionChangeEvent;
import com.bloomclient.client.api.event.WorldJoinEvent;
import com.bloomclient.client.api.event.WorldLeaveEvent;
import com.bloomclient.client.api.hud.HudElement;
import com.bloomclient.client.api.hud.HudLines;
import com.bloomclient.client.api.hud.HudWidget;
import com.bloomclient.client.api.hud.WidgetOption;
import com.bloomclient.client.api.settings.Setting;
import com.bloomclient.client.api.storage.WorldStorage;
import com.bloomclient.client.ui.BloomTheme;
import com.bloomclient.client.ui.Icon;
import com.bloomclient.client.ui.Ui;
import java.util.List;
import java.util.Locale;

/** Esempio: tempo trascorso nel mondo, record per mondo, widget HUD e pagina nel Bloom Menu. */
public final class SessionTimerMod implements BloomMod {
	/** Dati salvati per mondo in .bloom/worlds/<mondo>/example-session-timer/stats.json */
	static final class Stats {
		long longestSessionSeconds;
		int dimensionChanges;
	}

	private ModContext ctx;
	private Setting.Bool showSeconds;
	private Setting.Int warnAfterMinutes;
	private long joinedAt;
	private Stats stats = new Stats();

	@Override
	public ModMetadata metadata() {
		return new ModMetadata("example-session-timer", "Session Timer", "Time spent in the current world", "1.0.0", "Example",
				ModCategory.UTILITY, Icon.CLOCK);
	}

	@Override
	public void onInitialize(ModContext context) {
		this.ctx = context;
		context.settings().section("Display");
		showSeconds = context.settings().bool("showSeconds", true, "Show seconds");
		warnAfterMinutes = context.settings().integer("warnAfter", 60, 0, 240, 15, " min", "Reminder after")
				.describe("Action bar reminder (0 = off)");
		context.registerWidget(new SessionWidget());
		context.registerPage(new SessionPage());
	}

	@Override
	public void onEnable(ModContext context) {
		context.on(WorldJoinEvent.class, e -> start());
		context.on(WorldLeaveEvent.class, e -> stop());
		context.on(DimensionChangeEvent.class, e -> stats.dimensionChanges++);
		context.every(20 * 60, this::remind);
		if (context.game().inWorld()) start();
	}

	@Override
	public void onDisable(ModContext context) {
		stop();
	}

	private void start() {
		joinedAt = System.currentTimeMillis();
		WorldStorage world = ctx.storage().world();
		stats = world == null ? new Stats() : world.read("stats.json", Stats.class, Stats::new);
	}

	private void stop() {
		if (joinedAt == 0) return;
		stats.longestSessionSeconds = Math.max(stats.longestSessionSeconds, seconds());
		WorldStorage world = ctx.storage().world();
		if (world != null) world.write("stats.json", stats);
		joinedAt = 0;
	}

	private long seconds() {
		return joinedAt == 0 ? 0 : (System.currentTimeMillis() - joinedAt) / 1000;
	}

	private void remind() {
		int every = warnAfterMinutes.get();
		long minutes = seconds() / 60;
		if (every > 0 && minutes > 0 && minutes % every == 0) ctx.notify("You have been playing for " + minutes + " minutes");
	}

	private String format(long s) {
		return showSeconds.on() ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : (s / 60) + " min";
	}

	private final class SessionWidget extends HudWidget {
		SessionWidget() {
			super("example-session-timer:time", "Session time", "Time spent in this world", Icon.CLOCK);
		}

		@Override
		public int updateIntervalMs() {
			return 1000;
		}

		@Override
		public List<WidgetOption> options() {
			return List.of(WidgetOption.bool("record", "Show record", false));
		}

		@Override
		public boolean isAvailable(GameInfo game) {
			return joinedAt != 0;
		}

		@Override
		public void update(HudElement element, HudLines out, GameInfo game) {
			out.add("Session ", format(seconds()));
			if (element.option("record", false)) out.add("Record ", format(stats.longestSessionSeconds));
		}
	}

	private final class SessionPage implements ModPage {
		@Override
		public String id() {
			return "session";
		}

		@Override
		public String title() {
			return "Session";
		}

		@Override
		public Icon icon() {
			return Icon.CLOCK;
		}

		@Override
		public void render(Ui ui, int x, int y, int w, int h) {
			ui.round(x, y, w, 50, 6, BloomTheme.SURFACE);
			ui.text("Current session", x + 10, y + 10, BloomTheme.TEXT_MUTED);
			ui.textScaled(format(seconds()), x + 10, y + 24, 1.5f, BloomTheme.TEXT);
			ui.text("Record: " + format(stats.longestSessionSeconds) + " · dimension changes: " + stats.dimensionChanges, x, y + 60,
					BloomTheme.TEXT_DIM);
			if (ui.button("session.reset", x, y + 78, 110, 18, "Reset record", Ui.Kind.SECONDARY)) {
				stats.longestSessionSeconds = 0;
			}
		}
	}
}
```

Per attivarla: `mods.register(new SessionTimerMod());` in `BloomClient`.
Compare in **Mods** con interruttore, pagina "Session", scheda Impostazioni
generata dalle impostazioni e scheda Info; il widget compare nell'HUD Studio.

### Metadati

`ModMetadata(id, name, description, version, author, category, icon)`:
`id` minuscolo `[a-z0-9_.-]`, unico; `name` e `description` possono essere
testo o chiavi di traduzione `bloom.*`; `category` decide il filtro nella
pagina Mods (`HUD`, `WORLD`, `INVENTORY`, `UTILITY`, `VISUAL`); `icon` è una
delle icone Bloom (`Icon`). `enabledByDefault()` decide lo stato alla prima
installazione.

## 4. Eventi

| Evento | Quando | Note |
|---|---|---|
| `ClientTickEvent` | Fine di ogni tick client (20/s, anche nel menu principale) | Istanza riusata; `every(n)` per cadenze |
| `WorldJoinEvent(WorldIdentity)` | Ingresso in un mondo o server | Il `ModStorage.world()` è già disponibile |
| `WorldLeaveEvent(WorldIdentity)` | Uscita (anche alla chiusura del gioco) | Salvare qui i dati per mondo |
| `DimensionChangeEvent(world, from, to, lastPosition, newPosition)` | Cambio dimensione nello stesso mondo | Ultima posizione nella vecchia dimensione, prima nella nuova |
| `InventoryChangedEvent` | Inventario del giocatore cambiato (al massimo una volta per tick) | Basato sul contatore di modifiche dell'inventario: costo zero se non cambia nulla |

```java
context.on(InventoryChangedEvent.class, e -> dirty = true);    // segna
context.every(5, () -> { if (dirty) recompute(); });           // ricalcola al più ogni 5 tick
```

Il bus non alloca durante la consegna (array copy-on-write per tipo) e
consegna solo alle mod attive, ognuna isolata dalle altre.

## 5. Configurazioni

```java
SettingsGroup s = context.settings();
s.section("bloom.wt.settings.exploration");                     // intestazione (chiave o testo)
Setting.Bool track = s.bool("trackExploration", true, "Record exploration").describe("Visited chunks and trips");
Setting.Int radius = s.integer("radius", 1, 0, 4, 1, " chunk", "Explored radius");
Setting.Decimal scale = s.decimal("scale", 1f, 0.5f, 2f, 0.05f, "%.2f×", "Scale");
Setting.Choice<Mode> mode = s.choice("mode", Mode.FAST, m -> "Mode " + m, "Mode");
Setting.Color color = s.color("color", 0xFF5CE1A8, false, "Color");
Setting.Text name = s.text("name", "", 32, "Name");
track.onChange(v -> rebuild());
radius.visibleWhen(track::on);
```

- File: `config/bloom/mods/<id>.json` → `{"schema": 1, "values": {...}}`.
- Le chiavi sconosciute restano nel file; valori non validi tornano al predefinito.
- Salvataggio asincrono una volta al secondo se qualcosa è cambiato, e alla chiusura.
- Le impostazioni compaiono nella scheda **Impostazioni** della mod (controllo
  giusto per tipo, pulsante di ripristino) e nella **Ricerca** del menu, che
  porta direttamente all'impostazione evidenziata.

Le impostazioni di Bloom stesso usano lo stesso sistema (`BloomSettings`,
`config/bloom/client.json`).

## 6. Persistence API

```java
ModStorage storage = context.storage();
Path global = storage.globalDir();                 // .bloom/mods/<id>/
WorldStorage world = storage.world();              // .bloom/worlds/<mondo>/<id>/  (null fuori da un mondo)
MyData data = world.read("data.json", MyData.class, MyData::new);
world.write("data.json", data);                    // serializza ora, scrive in background
world.writeLater("big.json", () -> json(snapshot)); // serializza sul thread IO (passare una copia)
```

- Scritture atomiche (file temporaneo + spostamento) su un thread dedicato;
  più scritture dello stesso file si riducono all'ultima.
- Un file illeggibile viene copiato in `<nome>.broken` e si riparte dai
  predefiniti.
- `BloomStorage.flush()` (chiamato all'uscita) attende le scritture in sospeso.
- Identità del mondo (`WorldIdentity`): singleplayer `sp-<cartella>`,
  multiplayer `mp-<host>_<porta>`, Realms `realms-<nome>`. Mondi diversi non
  condividono mai dati.
- Ogni file delle mod ufficiali ha un campo `schema` per le migrazioni future.

## 7. Widget HUD

```java
public final class DayWidget extends HudWidget {
	public DayWidget() {
		super("my-mod:day", "Day", "Current world day", Icon.CLOCK);
	}

	@Override
	public int updateIntervalMs() {
		return 1000;                      // i dati si aggiornano al massimo una volta al secondo
	}

	@Override
	public List<WidgetOption> options() {
		return List.of(WidgetOption.bool("showLabel", "bloom.option.show_label", true));
	}

	@Override
	public void update(HudElement e, HudLines out, GameInfo game) {
		long day = net.minecraft.client.Minecraft.getInstance().level.getOverworldClockTime() / 24000L;
		out.add(e.option("showLabel", true) ? "Day " : "", Long.toString(day));
	}
}
```

Registrazione: `context.registerWidget(new DayWidget())` in `onInitialize`.

- `update` riempie righe "etichetta valore" (eventualmente con icona di un
  oggetto e colore del valore). L'etichetta usa il colore etichetta dello
  stile, il valore il colore testo.
- Il renderer disegna solo le righe in cache: misura e costruisce il testo
  solo quando il contenuto o lo stile cambiano; un frame non formatta nulla.
- Valori non disponibili: `isAvailable` restituisce `false` (elemento nascosto
  in gioco, segnaposto `unavailableText()` nello Studio). Non mostrare mai
  valori inventati.
- `applyDefaults(HudElement)` imposta ancoraggio e stile di un nuovo elemento.
- Un widget di una mod spenta resta nel layout ma non viene disegnato.

### Layout e stile

Un elemento (`HudElement`) è un'istanza di un widget: più elementi possono
usare lo stesso widget con stili diversi (duplicazione). Posizione =
ancoraggio (9 punti dello schermo) + scostamento in pixel GUI, quindi gli
elementi restano al loro posto a ogni risoluzione e scala GUI e crescono
lontano dal bordo a cui sono ancorati. Stile (`HudStyle`): colore testo ed
etichetta, sfondo (colore, opacità, on/off), bordo (colore, spessore),
angoli, padding, dimensione testo, font (Predefinito/Uniforme), ombra,
spaziatura righe, opacità, allineamento.

File `config/bloom/hud/layout.json`:

```json
{"schema": 1, "elements": [{"id": "…", "widget": "bloom:fps", "visible": true,
  "anchor": "TOP_LEFT", "x": 4, "y": 4, "style": {"textScale": 1.0, "…": "…"}, "options": {"showLabel": "true"}}]}
```

Le esportazioni hanno lo stesso formato (`config/bloom/hud/exports/hud-<data>.json`
e appunti). Le migrazioni sono in `HudManager.MIGRATIONS` (indice `n` porta
lo schema `n` a `n+1`); uno schema più recente viene letto per i campi noti.

### Widget forniti

| Widget | Proprietario | Dati |
|---|---|---|
| FPS, Coordinate, Bioma, Direzione, Memoria, Ping, Stato armatura | Bloom | `GameInfo`, runtime Java, equipaggiamento |
| Dimensione, Distanza dal luogo, Coordinate collegate, Panoramica mondo | World Tracker | posizione, punti salvati |
| Riepilogo inventario, Oggetto selezionato, Durabilità, Slot liberi, Progresso obiettivo | Inventory Manager | ultima analisi dell'inventario |
| Guida della Memoria (visibile solo durante una guida) | Memoria della base | destinazione della guida, posizione |

Il ping è disponibile solo in multiplayer (in singleplayer il server
integrato non ha latenza significativa: il widget lo dichiara).

## 8. Pagine delle mod

`ModPage.render(Ui ui, int x, int y, int w, int h)` disegna nell'area del
menu con il toolkit `Ui`: `button`, `iconButton`, `toggle`, `slider`,
`textField` (`TextInput`), `segmented`, `dropdown`, `colorField`,
`beginScroll/endScroll`, `tooltip`, testo e forme. Gli id dei controlli
devono essere unici nella pagina. Feedback: `Feedback.toast(...)` e
`Feedback.confirm(messaggio, azione)` per le azioni distruttive. Le pagine
compaiono come schede nel dettaglio della mod e nella ricerca.

## 9. Traduzioni

Senza Fabric API Minecraft non carica i file lingua dai jar delle mod. Bloom
legge `assets/bloom/lang/<codice>.json` dal classpath (`ui.Tr`) e risponde,
tramite `ClientLanguageMixin`, alle chiavi `bloom.*`, `key.bloom.*` e
`key.category.bloom.*`; le altre chiavi restano a Minecraft. Inglese e
italiano sono completi; l'inglese è il fallback. Testi delle mod: chiavi
`bloom.*` oppure testo semplice.

## 10. Compatibilità

| Aspetto | Stato |
|---|---|
| Minecraft | 26.3 (unica versione supportata dal jar) |
| Loader | Fabric Loader ≥ 0.20.1; Fabric API **non** richiesta e compatibile se presente (i tasti vengono aggiunti solo se mancanti) |
| Renderer | Motore Bloom attivo, spento o fermato: nessuna differenza per menu e HUD |
| Backend grafico | OpenGL e Vulkan di Minecraft (`renderpearl`): l'HUD usa la pipeline GUI vanilla |
| Shader | Con Iris il launcher usa OpenGL; l'HUD è disegnato nella passata GUI, dopo gli shader |
| Multiplayer | Nessun pacchetto inviato; teletrasporto solo in singleplayer con comandi consentiti (comando vanilla `/tp`); Inventory Manager non clicca e non sposta oggetti |
| Istanze "Java · Renderer vanilla" | Il launcher non aggiunge il jar: menu e HUD Bloom sono disponibili nelle istanze "Bloom Native · Rust" |

## 11. Prestazioni

- HUD: un passaggio per frame sugli elementi; gli aggiornamenti dati seguono
  l'intervallo di ogni widget (FPS 500 ms, coordinate 100 ms, memoria 1 s…);
  testo misurato solo quando cambia. Tempo medio visibile in F3 (voce
  `bloom:client`, attivabile da Opzioni di debug).
- `GameInfo` legge bioma solo quando cambia il blocco, ping e permessi una
  volta al secondo.
- Inventory Manager analizza solo quando l'inventario cambia.
- World Tracker: lavoro al secondo minimo (bitmap dei chunk), suggerimenti
  ogni 5 s sulle entità già renderizzate entro 64 blocchi; salvataggi grandi
  serializzati sul thread IO.

## 12. Anteprima di sviluppo

```powershell
$env:JAVA_TOOL_OPTIONS="-Dbloom.client.preview=home,mods,wt,im,settings,studio-select,hud -Dbloom.client.preview.quit=true"
.\gradlew runClient --args="--quickPlaySingleplayer bloom-bench"
```

Apre ogni schermata e salva `run/screenshots/bloom-preview-<nome>.png`.
Senza la proprietà non fa nulla.

Anteprime della Memoria della base: `bm`, `bm-containers`, `bm-villagers`
(la pagina con la scheda indicata) e `bm-guide` (avvia una guida verso il
primo contenitore ricordato e torna in gioco). Con
`-Dbloom.client.preview.bm.query=<testo>` la ricerca parte già compilata.

## 13. Memoria della base

Mod ufficiale `bloom-base-memory` (`mods.basememory`). Ricorda ciò che il
giocatore ha già visto in una schermata e risponde a "dove ho messo X?" e
"chi vende Ripristino?".

| Cosa | Come |
|---|---|
| Contenitori | Quando si chiude la schermata di un baule (anche doppio), barile, scatola di shulker, baule di ender, tramoggia o distributore. Il blocco è quello sotto il mirino all'apertura; se il numero di slot non corrisponde al blocco, non viene registrato nulla. In multiplayer si aspetta il primo pacchetto dei contenuti (`stateId` ≠ 0) |
| Baule di ender | Un unico contenitore per mondo (chiave `ender`): il contenuto è lo stesso in ogni baule di ender |
| Villici | Offerte, mestiere, livello e nome alla chiusura degli scambi. La posizione si aggiorna mentre il villico è renderizzato; se cambia mestiere le offerte vecchie vengono scartate |
| Ricerca | Nome localizzato, id e incantesimi (anche dei libri) degli oggetti, anche dentro scatole di shulker e sacchetti; più ciò che il giocatore porta con sé. Indice ricostruito solo quando i dati o la lingua cambiano |
| Guida | Colonna di particelle `END_ROD` sopra la destinazione (solo lato client, nessun pacchetto), widget HUD con freccia, distanza e dislivello, slot evidenziati quando si apre il contenitore giusto. Finisce quando si chiude la schermata della destinazione o con "Ferma guida" |
| Contenitori spariti | Con "Dimentica i contenitori rotti" vengono rimossi solo se il chunk è caricato, il giocatore è entro 48 blocchi e il blocco non ha più una block entity |
| Integrazioni | Nome del luogo più vicino da `WorldTrackerMod.nearestPointName`; oggetti mancanti dell'obiettivo attivo da `InventoryManagerMod.activeGoalNeeds` |

File in `.bloom/worlds/<mondo>/bloom-base-memory/`: `containers.json` e
`villagers.json` (`schema` 1). Gli oggetti sono salvati con `ItemStack.CODEC`
e tutte le loro componenti; il JSON di un contenitore viene codificato una
volta alla registrazione, quindi un oggetto di una mod rimossa per una
sessione non si perde al salvataggio successivo.

Limiti: Bloom conosce solo ciò che il giocatore ha aperto. Un contenitore
svuotato da altri resta com'era all'ultima apertura ("visto 2 giorni fa"). I
bauli su carrelli e barche non vengono ricordati, perché si spostano.
