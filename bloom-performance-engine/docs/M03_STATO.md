# Milestone 03: stato

Verificato il 2026-11-01 su Windows 11, i5-10400F (12 thread), GTX 1660
SUPER (driver 610.74), Minecraft 26.3, Fabric Loader 0.20.1. "Completo"
significa implementato, testato e provato in gioco; "sperimentale" significa
funzionante e misurato ma non usato dal gioco o non pronto per l'uso normale.

## Completo

| Area | Cosa | Prova |
|---|---|---|
| Block rendering | **Fluidi nativi in Rust** (port esatto di `FluidRenderer`: facce, altezze e angoli, overlay, back face, luce, UV del flusso), protocollo v2 | 9 test Rust; in gioco 147 571 fluidi nativi con il validatore su **ogni** sezione, 0 differenze, su Vulkan e OpenGL |
| Block rendering | Modelli vanilla e delle mod con classi statiche, block state, multipart, varianti pesate, UV dall'atlante, AO, luce, layer trasparenti (M02, invariati); adapter registrabili; fallback Java per geometrie dinamiche, renderer Java delle mod, vertex format propri | M02 + validatore |
| Entity rendering | Resta in Java (scelta motivata in [M03_ANALISI.md](M03_ANALISI.md)); misure di estrazione e submit di entità, block entity, particelle, preparazione feature, frame graph | In gioco: [BENCHMARK.md](BENCHMARK.md) |
| Compatibility layer | Tre modalità + motore spento, ordine override → database → analisi, analisi di agganci/trasformazioni/API/dipendenze, stati per mod | 5 test JUnit; prove reali con Fabric API, Lithium, Sodium, Iris |
| Adapter Fabric API | Concatenazione con Indigo, tinte da registro, handler e overlay dei fluidi, origine nell'aritmetica di Indigo, modelli con override d'ombra a Indigo | 2 748 sezioni validate, 0 differenze (Indigo in luce vanilla) |
| Shader | Rilevamento di core shader sostituite (a ogni reload), Iris e shaderpack; fallback: motore spento con Sodium/Iris, OpenGL con Iris | Iris + Complementary su OpenGL (attivo) e Vulkan (non applicato) |
| Database | JSON firmato Ed25519, indice con SHA-256, anti-rollback, copia nel jar + aggiornamento dal launcher, doppia verifica | 6 test JUnit, 2 test Rust nel launcher |
| Vulkan in gioco | Selezione del device per il backend Vulkan di Minecraft, diagnostica, VRAM da VMA, timestamp GPU | In gioco su Vulkan |
| Diagnostica F3 | 6 voci `bloom:*` nel registro F3 vanilla (vedi [DIAGNOSTICA.md](DIAGNOSTICA.md)) | Screenshot in `benchmarks/compat/*/f3.png` |
| Launcher | Impostazioni del motore per istanza nell'editor (non più dal `.json`), aggiornamento del database, OpenGL con Iris | Test Rust del launcher |
| Stabilità | Ritorno al percorso originale per errore, validazione fallita o incompatibilità; nessun tocco a rete, account, gameplay; nessuna mod disattivata | Prove di compatibilità |

## Sperimentale

| Area | Stato |
|---|---|
| **Backend Vulkan proprietario** (`bloom-vulkan`) | Device selection, swapchain, command buffer, descriptor, allocatore GPU, pipeline e cache, compilazione GLSL→SPIR-V, sincronizzazione, lifetime, buffer, diagnostica: implementati e provati sulla GPU (test d'integrazione e benchmark). **Non collegato al gioco**: niente texture, ordinamento traslucido, nebbia; vedi [VULKAN.md](VULKAN.md) |
| Validatore con luce viva | Scarta i campioni in cui il light engine ha cambiato la luce tra le due compilazioni; utile ma la luce resta letta dal mondo vivo, non da una copia |
| Prove di compatibilità | Una sessione per configurazione, una macchina, una GPU NVIDIA |

## Non implementato

| Richiesta | Perché |
|---|---|
| Loader di shaderpack proprio, shadow map, riflessi, volumetrici di Bloom | Iris è il riferimento e richiede Sodium; un'alternativa è un progetto a sé |
| Bloom Vulkan come renderer del gioco | Richiede interoperabilità con il `VkDevice` di Mojang o un backend completo per `renderpearl` |
| Entità in Rust | Nessun guadagno misurabile atteso: vedi le misure in [BENCHMARK.md](BENCHMARK.md) |
| AO di Indigo (`hybrid`) in Rust | Necessario per usare Bloom con Fabric API senza cambiare la configurazione di Indigo |
| Forge / NeoForge | Nessuna toolchain stabile per 26.3 |
| Tempo per singolo shader | Minecraft 26.3 non lo espone; c'è il tempo GPU del frame |
| Validation layer Khronos | Non installato sulla macchina di test |
