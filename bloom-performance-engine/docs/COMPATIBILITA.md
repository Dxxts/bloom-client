# Bloom Compatibility Layer

## Modalità

Per ogni mod installata il layer sceglie una modalità
(`compatibility/CompatibilityLayer.java`):

| Modalità | Significato | Come viene applicata |
|---|---|---|
| **Native Bloom Adapter** (`NATIVE_ADAPTER`) | Bloom conosce il codice di rendering della mod e lo riproduce o lo aggira in modo verificato | Classi di modello registrate come statiche (API `BloomRenderAdapters` o database), oppure un adapter scritto per quella mod (oggi: Fabric API) |
| **Automatic Compatibility Adapter** (`AUTOMATIC_ADAPTER`) | Nessun aggancio al terreno; i blocchi con modelli vanilla statici vanno in Rust, gli altri a Java blocco per blocco | Nessuna azione: la decisione per blocco è in `BlockStateTables` |
| **Original Java Renderer Fallback** (`JAVA_FALLBACK`) | I blocchi della mod (per namespace) vengono tassellati dal renderer Java | Gli stati del namespace diventano `FORCED_BY_COMPATIBILITY` |
| Motore spento (`ENGINE_OFF`) | La mod sostituisce o altera il renderer in un modo che Bloom non può riprodurre | Il motore non parte: tutto il gioco usa il renderer originale |

Nessuna modalità modifica la mod: Bloom non traduce codice Java in Rust e non
cambia il bytecode di altre mod. Le uniche trasformazioni sono i mixin di
Bloom, gli stessi in ogni modalità, validati dal confronto con vanilla.

## Ordine di decisione

1. **Override dell'utente**, `config/bloom-engine.json`:
   ```json
   { "modModes": { "nomemod": "JAVA_FALLBACK" } }
   ```
   Vince sempre: ogni decisione è reversibile senza toccare il codice.
   Valori sconosciuti vengono ignorati e segnalati nel report.
2. **Database di compatibilità** (vedi [DATABASE.md](DATABASE.md)), se ha una
   voce per mod, versione, loader e versione di Minecraft con stato diverso da
   `UNTESTED`. Una voce non basta per una mod che aggancia il terreno: serve
   `terrainHooksVerified: true`, cioè una validazione in gioco registrata.
3. **Analisi statica** (`CompatibilityAnalyzer`): sostituzioni del chunk
   renderer e agganci non verificati alle classi del terreno → motore spento
   (salvo `allowUnverified`); tutto il resto → adapter automatico.

Dopo la costruzione delle tabelle, le mod lasciate all'analisi vengono
precisate con i conteggi reali: se nessun blocco della mod ha un modello
statico verificato la mod risulta `JAVA_FALLBACK`; se usa classi registrate
con l'API risulta `NATIVE_ADAPTER`.

## Cosa rileva l'analizzatore

Letto da `fabric.mod.json`, dai config Mixin e dal constant pool delle classi
(`.bloom/compatibility-report.json`, una voce per mod):

| Campo | Contenuto |
|---|---|
| `renderMixinTargets` | Mixin sulle classi che Bloom aggancia o riproduce (`SectionCompiler`, `ModelBlockRenderer`, `BlockModelLighter`, `FluidRenderer`, `BufferBuilder`, …) |
| `renderTransformations` | Mixin su altre classi di rendering (entità, frame, `renderpearl`, particelle, F3) |
| `renderApis` | Chiamate dirette OpenGL/Vulkan, `renderpearl`, vertex format e buffer propri, pipeline proprie, renderer di entità e block entity, render type, post processing |
| `rendererDependencies` | Dipendenze o raccomandazioni verso altri renderer (Sodium, Iris, Indium, Fabric Renderer API, …) |
| `customBlockModels`, `shipsShaders` | Implementazioni di `BlockStateModel`/`BlockStateModelPart`, shader inclusi |
| `states` | Stati dei blocchi della mod: nativi automatici, nativi tramite adapter, fallback per motivo |
| `mode`, `modeSource`, `reasons` | Decisione e motivazione |
| `databaseStatus`, `knownIssues`, `vulkan`, `databaseTests` | Dalla voce del database, se c'è |

## Adapter per Fabric API (`FabricApiAdapter`, versione 1)

Analisi dei mixin di fabric-api 0.165.0+26.3 sulle classi del terreno:

| Modulo | Cosa fa | Come lo gestisce Bloom |
|---|---|---|
| `fabric-renderer-api-v1` | `@Redirect` di ogni `ModelBlockRenderer.tesselateBlock` in `SectionCompiler.compile` verso il renderer attivo (Indigo) | Il `@WrapOperation` di Bloom si concatena al redirect: i blocchi nativi lo saltano, gli altri passano da Indigo |
| `fabric-rendering-v1` | Tinte da `BlockColorRegistry` per stati senza tinte vanilla | Quegli stati vanno a Java (`CUSTOM_TINT_FACTORY`) |
| `fabric-rendering-fluids-v1` | Handler di rendering per fluido; trasparenza dell'overlay da registro | Fluidi con handler sostituito → Java; il flag d'overlay segue il registro |
| `fabric-renderer-indigo` | Solo un accessor alla cache AO vanilla | Nessuna azione |

Cosa ha mostrato il validatore (ogni sezione ricompilata col percorso
vanilla+Fabric e confrontata byte per byte):

1. Con la configurazione predefinita di Indigo (`ambient-occlusion-mode=hybrid`
   più correzioni) la luce liscia differisce da vanilla: Indigo **non** è
   vanilla. → Con questa configurazione Bloom si spegne e lo dice nel report.
2. Con `ambient-occlusion-mode=vanilla` restavano due differenze, entrambe di
   Indigo: l'origine dei blocchi con offset arrotondata in `double`
   (risolto: Java invia l'origine nell'aritmetica di Indigo) e la perdita
   dell'override di direzione d'ombra (risolto: quei modelli restano a Indigo).
3. Dopo le correzioni: **2 748 sezioni validate su 2 748, 0 differenze**,
   1,41 milioni di blocchi e 158 551 fluidi nativi.

Per usare Bloom con Fabric API basta quindi una riga in
`config/fabric/indigo-renderer.properties`:

```
ambient-occlusion-mode=vanilla
```

Il controllo è automatico all'avvio: con un'altra modalità il motore resta
spento e il report spiega perché. L'adapter vale solo per le versioni dei
moduli registrate nel database come validate; una nuova versione di Fabric API
torna "non verificata" finché non viene provata.

## Shader

| Sistema | Rapporto con Bloom | Verificato |
|---|---|---|
| Core shader vanilla e resource pack che li sostituiscono (`shaders/core/terrain.*`, `block.*`, …) | Bloom produce gli stessi vertici nello stesso formato: le core shader si applicano invariate. Rilevate a ogni reload (F3+T compreso) e mostrate in F3 | Rilevamento sì; nessun resource pack di shader provato in gioco |
| Iris + shaderpack | Iris richiede Sodium, che sostituisce il chunk renderer: **Bloom si spegne**, Iris disegna tutto. Bloom mostra in F3 lo shaderpack attivo | Sì, Complementary Reimagined r5.9.3 su OpenGL |
| Iris sul backend Vulkan di Minecraft | Iris si carica ma **non applica lo shaderpack**, senza errori | Sì, stesso setup su Vulkan. Il launcher passa a OpenGL quando trova Iris (fallback) |
| Post processing (`PostChain`: contorno entità, effetti spettatore) | Non toccato da Bloom | — |
| Shader reload | Le tabelle vengono ricostruite a ogni `SectionCompiler` nuovo; il conteggio dei reload è in F3 | Sì (reload in ogni sessione) |
| Shaderpack senza Iris | **Non implementato**: Bloom non ha un loader di shaderpack | — |

## Stato verificato (2026-11-01, MC 26.3, Fabric Loader 0.20.1, GTX 1660 SUPER)

| Configurazione | Backend | Risultato | Prova |
|---|---|---|---|
| Solo Bloom | Vulkan | Attivo, fluidi nativi, 0 differenze | Validatore su ogni sezione: 147 571 fluidi nativi, nessuna differenza |
| Fabric API 0.165.0, Indigo predefinito | Vulkan | **Motore spento** (luce di Indigo ≠ vanilla) | Decisione dal database, motivo nel report |
| Fabric API 0.165.0, Indigo `ambient-occlusion-mode=vanilla` | Vulkan | Attivo (`NATIVE_ADAPTER`) | 2 748 sezioni validate, 0 differenze; senza override, dal database: 695 validate |
| Lithium 0.26.3 | Vulkan | Attivo (`AUTOMATIC_ADAPTER`) | 688 sezioni validate, 0 differenze |
| Sodium 0.9.3-beta.1 | Vulkan e OpenGL | Motore spento, gioco funzionante | Sodium gira su entrambi i backend |
| Sodium + Iris 1.11.7 + Complementary | OpenGL | Motore spento, shaderpack attivo | Screenshot con ombre di Complementary |
| Sodium + Iris 1.11.7 + Complementary | Vulkan | Motore spento, **shaderpack non applicato** | Screenshot senza effetti; F3 Bloom lo segnala |
| Forge / NeoForge | — | Non supportati | — |

Il validatore scarta un campione (senza errore) solo quando la luce letta dal
percorso nativo è cambiata prima della ricompilazione vanilla: la luce viene
dal light engine vivo, non dalla copia della sezione, e una propagazione in
mezzo produce differenze legittime (osservato una volta con Lithium).

Classificazione degli stati vanilla di 26.3 (invariata dalla M02): 34 616
nativi, 86 senza modello, 835 con `skipRendering` personalizzato, 186 con
ombra dipendente dalla posizione. Fluidi: 2 modelli (acqua, lava), tutti gli
stati di acqua e lava verificati.

## Test ripetibili

```bash
scripts/run-compat-test.sh <etichetta> <opengl|vulkan> <jar>...   # validatore 1 sezione su 4
VALIDATE=1 scripts/run-compat-test.sh ...                            # ogni sezione
```

Copia i jar nel run di sviluppo, gioca una copia del mondo, raccoglie report,
benchmark e screenshot F3 in `benchmarks/compat/<etichetta>/`, poi rimuove le
mod. I risultati di questa milestone sono in quella cartella.
