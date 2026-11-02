# Bloom Native World Engine: architettura

## Cosa fa Rust e cosa resta in Java

| Componente | Linguaggio | Dove |
|---|---|---|
| Face culling (`Block.shouldRenderFace`, `skipRendering`, forme di occlusione e coppie di forme) | **Rust** | `chunk-mesher` |
| AO liscia e luce piatta (`BlockModelLighter`, tutte le tabelle `AdjacencyInfo`/`AmbientVertexRemap`) | **Rust** | `chunk-mesher/src/ao.rs`, `lib.rs` |
| Colore, tinta, emissione, `CardinalLighting`, matematica `ARGB`/`LightCoordsUtil` | **Rust** | `bloom-core` |
| Generazione dei vertici nel formato `BLOCK` (28 byte), separazione SOLID/CUTOUT/TRANSLUCENT, foglie forzate a SOLID | **Rust** | `chunk-mesher` |
| **Fluidi** (port di `FluidRenderer`: facce, altezze, angoli, overlay, luce, UV del flusso) — M03 | **Rust** | `chunk-mesher/src/fluid.rs` |
| Occlusion culling tra le facce della sezione (`VisGraph` → `VisibilitySet`) | **Rust** | `culling/visgraph.rs` |
| Piano di lettura della luce (solo l'intorno dei blocchi con facce visibili) | **Rust** | `Mesher::plan_light` |
| Statistiche native (istogramma dei tempi, contatori) | **Rust** | `profiler` |
| Scheduler con priorità dinamica, deduplicazione, cancellazione, generazioni | **Rust** | `world-processing` (benchmark offline) |
| Frustum e distance culling | **Rust** | `culling/frustum.rs` (benchmark, renderer futuro) |
| Snapshot del mondo: stati, luce, scelta delle varianti, tinte per bioma, origini dei blocchi con offset, tinta e flusso dei fluidi | Java | `SectionMesher` |
| Classificazione degli stati e dei fluidi (nativo o fallback, con motivo) | Java | `BlockStateTables` |
| Block entity, blocchi con modelli non verificati o geometrie dinamiche, fluidi delle mod | Java (vanilla) | invariati |
| Entità, block entity, particelle (estrazione, animazioni, submit) | Java (vanilla), **misurati** | `LevelExtractorMixin`, `LevelRendererMixin` |
| Compatibility layer, database firmato, adapter Fabric API, rilevamento shader | Java | `compatibility/` |
| F3 proprietario, timer GPU, draw call, VRAM | Java | `diagnostics/`, `profiling/` |
| Selezione del device Vulkan di Minecraft | Java | `vulkan/`, `VulkanBackendMixin` |
| Backend Vulkan proprietario (sperimentale, fuori dal gioco) | **Rust** | `bloom-vulkan`, `bloom-gpu-bench` |
| Ordinamento traslucido, upload GPU, draw (OpenGL **e** Vulkan di `renderpearl`) | Java (vanilla) | invariati |
| Scheduling in-game dei task di compilazione | Java (vanilla) | `SectionTaskDynamicQueue` |
| Profiling di frame e compilazioni, benchmark, validatore, analizzatore di compatibilità | Java | `profiling`, `benchmark`, `validation`, `compatibility` |

## Flusso di una sezione

```
worker vanilla ── SectionCompiler.compile ──────────────────────────────────────
  HEAD   SectionMesher.prepare
           Java: stati 20³ + modelli/tinte/origini 16³ ──► snapshot off-heap
           JNI planLight ──► Rust: maschera della luce + fluidi visibili
           Java: luce solo dove serve; tinta e flusso dei fluidi richiesti
           JNI meshSection ──► Rust: culling, AO, luce, vertici, fluidi, VisGraph
  loop vanilla: block entity, blocchi e fluidi Java/fallback
                (@WrapOperation salta i modelli e i fluidi già fatti in Rust)
  VisGraph.resolve ──► VisibilitySet calcolato in Rust
  prima di build(): memCopy dei vertici Rust nei BufferBuilder vanilla
  RETURN: profiler, eventuale validazione contro vanilla
──────────────────────────────────────────────────────────────────────────────
upload / sort / draw: vanilla, su OpenGL o Vulkan (renderpearl)
```

Una sola chiamata JNI di meshing per sezione (più `planLight`), mai per
blocco o per faccia.

## Vulkan

In gioco Bloom usa il backend Vulkan di Minecraft (`renderpearl`) e ne
gestisce la scelta del device, la diagnostica e le misure; il backend
Vulkan proprietario di Bloom esiste come crate separato e sperimentale, non
collegato al gioco. Motivi e stato in [VULKAN.md](VULKAN.md).

## Perché niente greedy meshing

Lo shader `terrain` vanilla campiona sotto-regioni di un atlante senza
ripetizione, e l'AO è per vertice. Unire facce stirerebbe le texture e
cambierebbe l'illuminazione. Il greedy meshing è possibile solo con un
formato di vertice e uno shader propri (UV ripetute all'interno dello
sprite), che romperebbero resource pack e shader pack. Non è stato applicato.

## Thread e memoria

- Rust gira nel worker vanilla che esegue il task: niente thread extra in
  competizione con i `core − 1` worker di Minecraft. Priorità per distanza,
  cancellazione e deduplicazione restano quelle vanilla.
- Un contesto nativo per thread: buffer di output, maschere e slot riusati,
  **0 allocazioni per sezione** a regime (misurato, vedi
  [BENCHMARK.md](BENCHMARK.md)). Nessun allocatore proprietario: un pool
  delimitato (`bloom-core::pool`) e il riuso delle capacità dei `Vec`.
- Lato Java, buffer off-heap per thread (snapshot da ~54 KB, risultato,
  maschera), allocati una volta.
- Le tabelle si sostituiscono in modo atomico (`Arc`). Una snapshot costruita
  per un'epoch precedente viene rifiutata (`STALE_TABLES`) e la sezione va a
  vanilla.

## Fallback e stabilità

- `config/bloom-engine.json` → `enabled: false`: puro vanilla.
- Errore nativo, panic (catturato con `catch_unwind`), tabelle non valide o
  validazione fallita: `BloomEngine.fail` registra l'errore e il messaggio
  nativo, spegne il motore per la sessione, libera tabelle e contesti; le
  sezioni successive sono compilate da vanilla. Rete, account e gameplay non
  vengono mai toccati.
- Blocchi non riproducibili esattamente (modelli di mod non verificati,
  `skipRendering` personalizzato, ombra dipendente dalla posizione, più di 3
  tinte, coppie di forme parziali sconosciute, letture di luce non pianificate)
  vengono rimandati a Java blocco per blocco: mai approssimati.

## Struttura

```
bloom-performance-engine/
  docs/        M03_ANALISI, M03_STATO, ANALISI_TECNICA, ARCHITETTURA, PROTOCOLLO,
               COMPATIBILITA, DATABASE, VULKAN, DIAGNOSTICA, BENCHMARK
  compat-db/   database di compatibilità firmato (json, .sig, index.json)
  native/      workspace Cargo
    crates/bloom-core       protocollo, tabelle (anche fluidi), port esatti di colore e luce, pool
    crates/chunk-mesher     mesher, AO, fluidi, piano della luce, test
    crates/culling          VisGraph, frustum, distance culling
    crates/world-processing scheduler parallelo con priorità
    crates/profiler         contatori e istogrammi lock-free
    crates/bloom-ffi        libreria JNI bloom_native (zero dipendenze esterne)
    crates/bloom-bench      scene sintetiche + benchmark CPU ripetibile
    crates/bloom-vulkan     backend Vulkan sperimentale (ash, naga, windows-sys)
    crates/bloom-gpu-bench  benchmark GPU del backend sperimentale
  java/        mod Fabric (Loom 1.19, MC 26.3, Java 25)
    bridge/ mesh/ mixin/ access/ compatibility/ diagnostics/ profiling/
    validation/ vulkan/ benchmark/        test JUnit in src/test
  benchmarks/  results/ (JSON), compat/ (prove di compatibilità con log e F3)
  scripts/     build-native.ps1, run-benchmark.ps1, run-compat-test.sh, CompatDbTool.java
```

Le dipendenze esterne riguardano solo i crate Vulkan: la DLL caricata dal
gioco (`bloom-ffi` e ciò che usa) resta senza dipendenze esterne.

Forge e NeoForge non sono integrati: il loader reale del progetto e
l'unico con toolchain stabile per 26.3 è Fabric.
