# Compatibilità con le mod

## Tre modalità

| Modalità | Come funziona | Stato |
|---|---|---|
| **Automatic Adapter** | Le classi vanilla `SingleVariant`, `WeightedVariants`, `MultiPartModel` e `SimpleModelWrapper` sono riconosciute per classe esatta: le loro parti dipendono solo dallo stato e dal seed, che Java riproduce. Le proprietà dei blocchi (`skipRendering`, `getShadeBrightness`) sono verificate via reflection sulla classe che dichiara il metodo. | Implementato |
| **Native Adapter** | Una mod con modelli statici dichiara le proprie classi: `BloomRenderAdapters.registerStaticModel(...)` / `registerStaticPart(...)`. | API implementata, nessuna mod esterna la usa ancora |
| **Java Fallback** | Tutto il resto viene tassellato dal renderer vanilla, blocco per blocco, nella stessa sezione. | Implementato |

Non c'è traduzione di codice Java in Rust né modifica del bytecode di altre
mod: Bloom aggiunge solo i propri mixin (`@WrapOperation`, `@Inject`), che si
concatenano con quelli delle altre mod.

## Compatibility Analyzer

All'avvio scrive `.bloom/compatibility-report.json` con, per ogni mod:

- target dei mixin tra le classi del terrain (`SectionCompiler`,
  `ModelBlockRenderer`, `BlockModelLighter`, `BufferBuilder`, ...);
- API grafiche referenziate (OpenGL, Vulkan, renderpearl, vertex format e
  buffer personalizzati, block entity renderer, entity renderer, layer del
  terrain, render type e trasparenze), lette dal constant pool delle classi;
- numero di implementazioni di `BlockStateModel`/`BlockStateModelPart`;
- shader inclusi;
- verdetto: `NO_RENDER_HOOKS`, `RENDERS_OUTSIDE_TERRAIN`,
  `UNVERIFIED_TERRAIN_HOOKS`, `REPLACES_CHUNK_RENDERER`.

Con mod `UNVERIFIED_TERRAIN_HOOKS` o `REPLACES_CHUNK_RENDERER` il motore
resta **spento** (salvo `allowUnverified: true`, a rischio dell'utente).

## Stato verificato (2026-10-30)

| Configurazione | Risultato | Come è stato verificato |
|---|---|---|
| Minecraft 26.3 + Fabric Loader 0.20.1 + Bloom | Funziona: 34 616 stati su 35 723 nativi, 0 differenze nelle mesh | Client reale; validatore attivo su 1 sezione ogni 4 su 823 sezioni |
| Sodium 0.9.3, Iris 1.11.7 (26.3) | Bloom si disattiva (sostituiscono il chunk renderer) | Regola dell'analizzatore; **non provato in gioco** |
| Fabric API 0.165 (renderer Indigo) | **Non verificato** | Da provare: l'analizzatore lo segnalerà se aggancia le classi del terrain |
| Lithium 0.26.3 | **Non verificato** | Non tocca il rendering secondo l'analisi; da provare |
| Forge / NeoForge | Non supportati in questa milestone | — |

Classificazione degli stati vanilla di 26.3 (misurata in gioco):

| Esito | Stati |
|---|---|
| Nativo (Rust) | 34 616 |
| Non è un modello (aria, fluidi, block entity animate) | 86 |
| `skipRendering` personalizzato (vetri piani, sbarre, liquidi, radici di mangrovia, neve polverosa) | 835 |
| Ombra dipendente dalla posizione / forma dinamica | 186 |

Nessuna mod viene dichiarata compatibile senza una prova: le righe "non
verificato" restano tali finché non vengono eseguite col validatore attivo
(`validateEvery`).
