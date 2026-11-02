# Vulkan

Due parti distinte, con stato diverso.

## 1. In gioco: il backend Vulkan di Minecraft, gestito da Bloom (completo)

Minecraft 26.3 ha già un backend Vulkan (`renderpearl`, opzione
`preferredGraphicsBackend`). Bloom non crea un secondo `VkDevice` nel gioco:
due device non possono condividere buffer, texture e sincronizzazione senza
un'interoperabilità esplicita (memoria esterna, semafori condivisi), e il
frame di Minecraft (entità, GUI, post processing, OIT) resterebbe comunque
su quello di Mojang. La strategia è:

| Funzione | Come | Dove |
|---|---|---|
| Mesh valide su entrambi i backend | Il mesher scrive il formato `BLOCK` di `renderpearl`; il backend sceglie OpenGL o Vulkan | M02, invariato |
| **Selezione del device** | `VulkanBackend.findPhysicalDevice` prende il primo device adatto (preferendo il discreto). Con `vulkanDevice` in `config/bloom-engine.json` o `-Dbloom.vulkan.device=<indice o parte del nome>` Bloom sceglie il device indicato, purché superi il controllo di idoneità di Minecraft; altrimenti resta la scelta vanilla | `VulkanBackendMixin`, `VulkanDeviceSelection` |
| Diagnostica del device | Nome, backend, driver, estensioni, esito della selezione | F3 `bloom:gpu` |
| VRAM | Budget VMA per gli heap device-local dell'allocatore di Minecraft | `VramProbe` |
| Tempo GPU | Timestamp `renderpearl` (`createTimestampQueryPool`, `writeTimestamp`) | `GpuFrameTimer` |
| Draw call | Contate in `FrontendRenderPass`, comune ai due backend | `FrontendRenderPassMixin` |
| Fallback | OpenGL resta il backend alternativo; Vulkan non è obbligatorio. Il launcher sceglie Vulkan per le istanze Bloom solo se l'utente non ha scelto, e OpenGL quando c'è Iris | launcher `engine/mod.rs` |

## 2. Backend Vulkan proprietario (sperimentale, fuori dal gioco)

Crate `native/crates/bloom-vulkan`, provato dal benchmark
`native/crates/bloom-gpu-bench`. **Non è collegato al gioco** e non è nella
DLL del motore: serve a misurare un percorso Vulkan di Bloom sulle mesh reali
del mesher prima di decidere se e come portarlo in gioco.

| Componente richiesto | Implementazione | Test |
|---|---|---|
| Device selection | Enumerazione, idoneità (Vulkan ≥ 1.2, coda grafica, swapchain se serve), punteggio (discreto > integrato > virtuale > CPU, poi VRAM), preferenza per indice o nome; una preferenza esplicita non adatta è un errore, non un ripiego silenzioso | 3 test unitari |
| Swapchain | Finestra Win32, surface, swapchain FIFO o IMMEDIATE/MAILBOX, ricreazione su resize/out-of-date | Benchmark `--present` |
| Command buffer | Un command pool per frame in volo, reset del pool intero; submit one-shot con fence per gli upload | Test GPU, benchmark |
| Descriptor management | Cache dei layout per binding, allocatore che aggiunge pool quando uno si esaurisce | Test GPU |
| GPU memory allocation | Blocchi da 64 MiB per tipo di memoria, first fit con allineamento e coalescenza, allocazioni dedicate oltre metà blocco, statistiche | 3 test unitari (incluso 5 000 operazioni casuali senza sovrapposizioni) |
| Pipeline management | Cache per descrizione + `VkPipelineCache` del driver | Test GPU |
| Shader compilation | GLSL 450 → SPIR-V con naga (Rust puro, validato), cache per hash | 2 test unitari |
| Synchronization | Fence per frame in volo, semafori di acquisizione/presentazione, dipendenza di subpass | Test GPU, benchmark |
| Resource lifetime | Coda di distruzione differita indicizzata per frame completato | Benchmark (resize) |
| Buffer management | Buffer device-local con upload tramite staging, host-visible mappati, readback | Test GPU |
| GPU diagnostics | Report del device, budget di memoria `VK_EXT_memory_budget`, timestamp per frame, contatori di draw/bind, messaggi `VK_EXT_debug_utils` | Test GPU, benchmark |

Test d'integrazione (`tests/gpu.rs`): crea device, allocatore, pipeline e
descrittori, disegna un quad con draw diretto e con multi-draw indirect,
legge i pixel e i timestamp. Passa sulla GTX 1660 SUPER; si salta da solo
su macchine senza Vulkan.

### Misure (2026-11-01, GTX 1660 SUPER, driver 610.74)

`bloom-gpu-bench --sections-x 16 --frames 600`: 1 024 sezioni meshate dal
mesher Bloom, 1 920×1 080 offscreen, telecamera che gira attorno alla griglia.
I risultati sono in `benchmarks/results/gpu-bench-*.json`.

| Scena | Quad | Modo | Draw call/frame | GPU media | GPU p99 | CPU per registrare |
|---|---|---|---|---|---|---|
| Terreno naturale | 396 166 | diretto | 511 | 0,548 ms | 1,034 ms | 0,029 ms |
| Terreno naturale | 396 166 | multi-draw indirect | 1 | 0,501 ms | 0,816 ms | 0,013 ms |
| Oceano | 219 350 | diretto | 512 | 0,280 ms | 0,576 ms | 0,028 ms |
| Oceano | 219 350 | multi-draw indirect | 1 | 0,276 ms | 0,581 ms | 0,011 ms |

Memoria usata dal backend: 59 MiB (terreno), 40 MiB (oceano). Shader
compilati in 1,2 ms. Con finestra e swapchain (`--present`, 1 584×861):
GPU 0,509 ms diretto, 0,436 ms indiretto. Il validation layer Khronos non è
installato su questa macchina: i messaggi `debug_utils` sono 0, ma la
validazione completa non è stata eseguita.

Cosa **non** dicono questi numeri: non sono confrontabili con il frame di
Minecraft, che disegna texture, layer ordinati, entità, cielo, nuvole, OIT e
post processing. Misurano il costo di Bloom per disegnare le proprie mesh.

### Cosa manca per usarlo in gioco (non implementato)

- Texture atlas, mipmap e campionamento (oggi un checker al posto della texture).
- Ordinamento e blending del layer traslucido, nebbia, lightmap dinamica.
- Integrazione con il frame di Minecraft: o interoperabilità con il
  `VkDevice` di `renderpearl` (memoria esterna e semafori condivisi), o un
  `GpuDeviceBackend` Bloom per `renderpearl`, che significa implementare
  l'intera API grafica del gioco (≈ 40 classi del backend Vulkan di Mojang).
- Validazione con il validation layer Khronos (non installato qui).
