# Bloom Native World Engine: architettura

## Cosa fa Rust e cosa resta in Java

| Componente | Linguaggio | Dove |
|---|---|---|
| Face culling (`Block.shouldRenderFace`, `skipRendering`, forme di occlusione e coppie di forme) | **Rust** | `chunk-mesher` |
| AO liscia e luce piatta (`BlockModelLighter`, tutte le tabelle `AdjacencyInfo`/`AmbientVertexRemap`) | **Rust** | `chunk-mesher/src/ao.rs`, `lib.rs` |
| Colore, tinta, emissione, `CardinalLighting`, matematica `ARGB`/`LightCoordsUtil` | **Rust** | `bloom-core` |
| Generazione dei vertici nel formato `BLOCK` (28 byte), separazione SOLID/CUTOUT/TRANSLUCENT, foglie forzate a SOLID | **Rust** | `chunk-mesher` |
| Occlusion culling tra le facce della sezione (`VisGraph` → `VisibilitySet`) | **Rust** | `culling/visgraph.rs` |
| Piano di lettura della luce (solo l'intorno dei blocchi con facce visibili) | **Rust** | `Mesher::plan_light` |
| Statistiche native (istogramma dei tempi, contatori) | **Rust** | `profiler` |
| Scheduler con priorità dinamica, deduplicazione, cancellazione, generazioni | **Rust** | `world-processing` (benchmark offline) |
| Frustum e distance culling | **Rust** | `culling/frustum.rs` (benchmark, renderer futuro) |
| Snapshot del mondo: stati, luce, scelta delle varianti, tinte per bioma, offset | Java | `SectionMesher` |
| Classificazione degli stati (nativo o fallback, con motivo) | Java | `BlockStateTables` |
| Fluidi, block entity, blocchi con modelli non verificati | Java (vanilla) | invariati |
| Ordinamento traslucido, upload GPU, draw (OpenGL **e** Vulkan di `renderpearl`) | Java (vanilla) | invariati |
| Scheduling in-game dei task di compilazione | Java (vanilla) | `SectionTaskDynamicQueue` |
| Profiling di frame e compilazioni, benchmark, validatore, analizzatore di compatibilità | Java | `profiling`, `benchmark`, `validation`, `compatibility` |

## Flusso di una sezione

```
worker vanilla ── SectionCompiler.compile ──────────────────────────────────────
  HEAD   SectionMesher.prepare
           Java: stati 20³ + modelli/tinte/offset 16³ ──► snapshot off-heap
           JNI planLight ──► Rust: maschera delle posizioni di luce necessarie
           Java: luce solo dove serve
           JNI meshSection ──► Rust: culling, AO, luce, vertici, VisGraph
  loop vanilla: fluidi, block entity, blocchi Java/fallback
                (@WrapOperation salta i blocchi già fatti in Rust)
  VisGraph.resolve ──► VisibilitySet calcolato in Rust
  prima di build(): memCopy dei vertici Rust nei BufferBuilder vanilla
  RETURN: profiler, eventuale validazione contro vanilla
──────────────────────────────────────────────────────────────────────────────
upload / sort / draw: vanilla, su OpenGL o Vulkan (renderpearl)
```

Una sola chiamata JNI di meshing per sezione (più `planLight`), mai per
blocco o per faccia.

## Perché niente backend Vulkan proprietario in questa milestone

Minecraft 26.3 ha già un backend Vulkan nativo (`renderpearl/backend/vulkan`),
selezionabile con `preferredGraphicsBackend:vulkan` e con fallback automatico
su OpenGL. Un `VkDevice` creato da Rust non potrebbe condividere buffer e
sincronizzazione con quello di Minecraft senza un'interoperabilità esplicita.
Producendo i dati nel formato di `renderpearl`, il mesher Rust funziona con
**entrambi** i backend senza codice aggiuntivo. Un renderer nativo completo,
con device proprio, va affrontato come milestone separata insieme alla
gestione dell'intero frame (entità, GUI, post-processing).

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
  docs/        ANALISI_TECNICA, ARCHITETTURA, PROTOCOLLO, COMPATIBILITA, BENCHMARK
  native/      workspace Cargo (zero dipendenze esterne)
    crates/bloom-core       protocollo, tabelle, port esatti di colore e luce, pool
    crates/chunk-mesher     mesher, AO, piano della luce, test
    crates/culling          VisGraph, frustum, distance culling
    crates/world-processing scheduler parallelo con priorità
    crates/profiler         contatori e istogrammi lock-free
    crates/bloom-ffi        libreria JNI bloom_native
    crates/bloom-bench      benchmark offline ripetibile
  java/        mod Fabric (Loom 1.19, MC 26.3, Java 25)
    bridge/ mesh/ mixin/ access/ compatibility/ profiling/ validation/ benchmark/
  benchmarks/results/  risultati JSON
  scripts/     build-native.ps1, run-benchmark.ps1
```

Forge e NeoForge non sono integrati: il loader reale del progetto e
l'unico con toolchain stabile per 26.3 è Fabric.
