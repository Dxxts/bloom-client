# Bloom Diagnostics (F3)

Le voci Bloom sono registrate nel registro F3 di Minecraft 26.3
(`DebugScreenEntries`): compaiono nella schermata delle opzioni di debug
(F3+F6) accanto a quelle vanilla e si attivano o disattivano allo stesso
modo. Al primo avvio vengono messe "nell'overlay" una sola volta (file
`.bloom/f3-defaults-applied`); dopo, decide il giocatore.

| Voce | Righe | Fonte |
|---|---|---|
| `bloom:engine` | Stato del motore (attivo, spento con motivo, fallito), fluidi nativi sì/no; stati nelle tabelle, stati di fluido nativi, tempo di costruzione | `BloomEngine`, `BlockStateTables` |
| `bloom:block_render` | **Block render time**: sezioni al secondo e tempo medio per sezione, percorso nativo e Java; snapshot Java e mesh Rust separati; **fallback count**: blocchi, fluidi e sezioni andati a Java | `EngineProfiler` (finestra di 1 s) |
| `bloom:entity_render` | **Entity render time**: entità visibili, estrazione e submit; block entity; particelle; preparazione delle feature (media sugli ultimi 128 frame) | `RenderStats` |
| `bloom:gpu` | Tempo GPU per frame (timestamp), tempo CPU del frame graph; **draw call** e draw (le chiamate multi-draw ne contano più d'uno); terreno in multi-draw indirect o diretto; **GPU pipeline information**: backend e device; VRAM; **Vulkan device information**: esito della selezione Bloom, numero di estensioni | `GpuFrameTimer`, `RenderStats`, `VramProbe`, `VulkanDeviceSelection` |
| `bloom:shaders` | **Shader**: core shader vanilla o sostituite da resource pack (con il pack), stato di Iris (shaderpack, e se non è applicato perché il backend è Vulkan), numero di reload | `ShaderCompatibility` |
| `bloom:compatibility` | **Compatibility adapter status**: mod per modalità (native, automatic, Java fallback, engine off), database in uso (copia, sequenza, voci); **rendering errors**: numero e ultimo messaggio | `CompatibilityReport`, `RenderingErrors` |

"Shader execution information": Minecraft 26.3 non espone il tempo per
singolo shader o passata; Bloom mostra il tempo GPU dell'intero frame
(timestamp all'inizio e alla fine di `Minecraft.runTick`, lo stesso
intervallo del `TimerQuery` vanilla). Comprende le attese della GPU verso la
CPU: in un frame limitato dalla GPU coincide con il tempo GPU del frame.

## Costi

- Timestamp GPU: attivi solo mentre la voce `bloom:gpu` è visibile (o
  durante un benchmark); si spengono da soli dopo 120 frame senza richieste.
- Draw call e tempi di estrazione/submit: un incremento per draw e due
  `System.nanoTime()` per fase per frame, sempre attivi.
- VRAM: una lettura al secondo al massimo.

## File

| File | Contenuto |
|---|---|
| `.bloom/engine-stats.json` | Ogni `statsIntervalSeconds`: tempi di compilazione, frame, `render` (fasi per frame, draw call, entità), `gpuFrame`, sezioni validate e scartate |
| `.bloom/compatibility-report.json` | Decisioni per mod, database, shader, stati per motivo |
| `.bloom/validation-failure.json` | La prima differenza trovata dal validatore |
| `.bloom/benchmarks/*.json` | Risultati di `runBenchmark`, con backend, device, VRAM, mod installate |

Screenshot di riferimento in `benchmarks/compat/*/f3.png` (overlay attivo
alla fine di ogni prova di compatibilità).
