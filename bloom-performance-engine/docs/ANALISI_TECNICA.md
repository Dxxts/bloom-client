# Bloom Native World Engine: analisi tecnica (Fase 1)

Data dell'analisi: 2026-10-30. Le fonti sono il codice di questo repository,
il jar client di Minecraft 26.3 installato dal launcher Bloom
(`%APPDATA%\com.bloomclient.launcher\game\versions\26.3\26.3.jar`),
decompilato con Fernflower, e le API pubbliche di Fabric Meta e Modrinth.
Nessuna affermazione di questo documento si basa su supposizioni sul codice di
Minecraft: ogni riferimento indica la classe letta.

## 0. Stato del progetto esistente

Bloom Client, fino alla Milestone 01, è **solo un launcher** (Tauri 2 + React).
Il repository non contiene mod in-game, renderer, mixin o codice Java.

| Domanda | Risposta verificata |
|---|---|
| Versioni Minecraft supportate | Tutte quelle del manifest Mojang; testate 26.3 e 1.8.9 (README). Il target di questa milestone è **26.3** (release del 2026-09-15, l'ultima stabile installata). |
| Versione Java | 26.3 richiede **Java 25** (`javaVersion.majorVersion = 25`, componente `java-runtime-epsilon`). |
| Loader | Il launcher supporta solo **Vanilla**. `LoaderAdapter` per Fabric/Forge/NeoForge esiste solo come interfaccia (`src-tauri/src/instances/loaders.rs`). |
| Mod di ottimizzazione già presenti | Nessuna nel progetto. |

Di conseguenza "il renderer attuale" è il **renderer vanilla di Minecraft
26.3**. Le sezioni seguenti lo analizzano.

## 1. Architettura attuale (Minecraft 26.3, vanilla)

### 1.1 Pipeline grafica

26.3 ha un'astrazione GPU propria, `com.mojang.renderpearl`:

- `api/` (device, buffer, pipeline, texture, comandi) e `backend/api/`
  (`GpuDeviceBackend`, `CommandEncoderBackend`, `RenderPassBackend`,
  `SpvModule` con reflection SPIR-V).
- `backend/opengl/` (63 classi: `GlDevice`, `GlBuffer`, `DirectStateAccess`,
  `BufferStorage`, cache dei framebuffer, ricompilazione delle pipeline).
- `backend/vulkan/` (39 classi più `checkpoints/` e `init/`): `VulkanDevice`,
  `VulkanQueue` con semafori, `VulkanCommandPool`, `VulkanTransientMemory`,
  `DestructionQueue` per la distruzione differita delle risorse, checkpoint
  NVIDIA/AMD per la diagnosi dei device lost.
- Librerie: `lwjgl-vulkan`, `lwjgl-vma` (allocatore di memoria GPU),
  `lwjgl-shaderc` e `lwjgl-spvc` (GLSL → SPIR-V e ritorno).
- Selezione del backend: `PreferredGraphicsApi` (`default`, `opengl`,
  `vulkan`), opzione `preferredGraphicsBackend` di `options.txt`. Con
  `vulkan` prova Vulkan e ripiega su OpenGL; con `default`/`opengl` fa
  l'inverso.

**Conseguenza architetturale fondamentale:** Minecraft possiede già un
backend Vulkan nativo con un proprio `VkDevice`. Un secondo device Vulkan
scritto in Rust non potrebbe condividere buffer, texture e sincronizzazione
con quello di Minecraft senza interoperabilità esplicita (memoria esterna e
semafori condivisi), e duplicherebbe un componente che Mojang mantiene. La
strategia corretta è produrre dati compatibili con `renderpearl`: così il
lavoro di Bloom vale per entrambi i backend (vedi §6).

### 1.2 Rendering dei chunk

Classi in `net.minecraft.client.renderer.chunk`:

| Classe | Ruolo |
|---|---|
| `SectionRenderDispatcher` | Coda di compilazione, worker, upload. Mantiene uno `StagingBuffer` da 98 MiB e, per ogni layer, due `UberGpuBuffer` (vertici fino a 128 MiB, indici fino a 32 MiB) sub-allocati con un `TlsfAllocator`. |
| `SectionTaskDynamicQueue` | Coda con priorità dinamica: estrae il task più vicino alla camera (`poll(cameraPosition)`). |
| `RenderSection.CompileTask` | Task di compilazione con flag `isCancelled`/`isCompleted`. |
| `SectionCopy` / `RenderSectionRegion` / `RenderRegionCache` | Copia immutabile 3×3×3 di sezioni (palette dei blocchi e block entity) letta dai worker. La luce è letta dal `LevelLightEngine` vivo. |
| `SectionCompiler` | **Tassellazione** di una sezione 16×16×16. |
| `VisGraph` / `VisibilitySet` | Flood fill 16³ per l'occlusion culling ("cave culling") fra le facce della sezione. |
| `ChunkSectionsToRender` | Disegno con `DrawSeparate`, `DrawIndirect` o `GpuMultiDrawIndexedIndirect`. |
| `ChunkSectionLayer` | Tre layer: `SOLID` e `CUTOUT` (buffer da 4 MiB), `TRANSLUCENT` (768 KiB, ordinato). |

### 1.3 Generazione delle mesh

`SectionCompiler.compile` visita i 4096 blocchi della sezione. Per ognuno:

1. `isSolidRender()` → `VisGraph.setOpaque`.
2. Block entity → aggiunta ai risultati (disegnate a parte).
3. Fluido → `FluidRenderer.tesselate`.
4. `RenderShape.MODEL` → `ModelBlockRenderer.tesselateBlock`:
   - `model.collectParts(random)` con seed `blockState.getSeed(pos)`;
   - per ogni direzione, `Block.shouldRenderFace` (forma di occlusione del
     vicino, `skipRendering`, cache LRU di coppie di forme);
   - AO liscia (`BlockModelLighter.prepareQuadAmbientOcclusion`, ~20
     `getBlockState` e ~9 letture di luce per faccia, cache LRU da 100
     elementi per thread) oppure illuminazione piatta;
   - tinta per bioma (`BlockTintSource.colorInWorld`);
   - scrittura dei vertici con `BufferBuilder.addVertex`.
5. `MeshData` per layer; il layer traslucido viene ordinato
   (`MeshData.sortQuads`).

Formato dei vertici `DefaultVertexFormat.BLOCK`: 28 byte (posizione
`RGB32_FLOAT`, colore `RGBA8_UNORM`, UV0 `RG32_FLOAT`, luce UV2 `RG16_SINT`).
Primitive `QUADS`, indici generati.

### 1.4 Gestione dei buffer grafici

Già avanzata: `ByteBufferBuilder` off-heap per ogni layer, pool di
`SectionBufferBuilderPack` (`SectionBufferBuilderPool`), staging buffer
unico, uber-buffer con TLSF, upload batch in `uploadTerrainBuffersToGpu`
protetto da `copyLock`.

### 1.5 Caricamento dei chunk e thread

I chunk arrivano dalla rete (o dal server integrato) sul thread principale.
`SectionRenderDispatcher` esegue i task su un `TracingExecutor` (pool di
background di Minecraft): ogni worker estrae il task più vicino e lo esegue.
I risultati vengono caricati sulla GPU dal thread di rendering.

### 1.6 Illuminazione

Luce cielo e blocchi dal `LevelLightEngine` (nibble per sezione). Per i
vertici: `LightCoordsUtil.getLightCoords` (con `emissiveRendering` e
`getLightEmission`), `smoothBlend` e `smoothWeightedBlend` per l'AO,
`CardinalLighting` (DEFAULT 0.5/1.0/0.8/0.8/0.6/0.6; NETHER) per l'ombreggiatura
direzionale.

### 1.7 Entità e block entities

Fuori dalla mesh delle sezioni: le block entity vengono raccolte da
`SectionCompiler` e disegnate ogni frame dai rispettivi renderer; le entità
seguono la propria pipeline. Questa milestone non le modifica.

### 1.8 Ecosistema delle mod per 26.3 (Modrinth, 2026-10-30)

| Mod | Versione per 26.3 (Fabric) | Effetto su Bloom |
|---|---|---|
| Sodium | `mc26.3-0.9.3-beta.1` | Sostituisce `SectionRenderDispatcher`/`SectionCompiler`. **Bloom si disattiva** se Sodium è presente: non va sostituito un renderer già ottimizzato con uno non misurato. |
| Iris | `1.11.7+26.3` | Richiede Sodium → stesso caso. |
| Lithium | `mc26.3-0.26.3` | Ottimizza logica server/tick, non il rendering. Compatibile in linea di principio; da verificare. |
| VulkanMod | nessuna versione per 26.3 | Non applicabile (26.3 ha già Vulkan). |
| Fabric API | `0.165.0+26.3` | Il suo renderer (Indigo) aggancia la tassellazione dei blocchi: da verificare con il Compatibility Analyzer. |

Fabric Loader 0.20.1 supporta 26.3 senza intermediary (`intermediary 0.0.0`):
dalla 26.1 il jar non è offuscato e le mod usano direttamente i nomi Mojang.
Toolchain: Fabric Loom 1.19, Gradle 9.8.

## 2. Colli di bottiglia individuati

Analisi del codice; i numeri arriveranno dal benchmark della Fase 2.

1. **Tassellazione per-blocco in Java (`SectionCompiler.compile`).** Per ogni
   faccia visibile con AO: circa 20 `getBlockState` (con indirezione su
   palette e `SectionCopy`), 9 calcoli di luce attraverso
   `LevelLightEngine` (hash map di DataLayer) e cache `Long2*LinkedOpenHashMap`
   da 100 elementi che in una sezione densa si svuotano di continuo. È il
   candidato principale per Rust.
2. **`VisGraph.resolve`**: flood fill con `BitSet` e `IntArrayFIFOQueue` su
   4096 celle, per ogni sezione. Calcolo puro, portabile in Rust.
3. **Ordinamento traslucido** (`MeshData.sortQuads`) a ogni ricompilazione e
   a ogni `ResortTransparencyTask`. Secondario.
4. **Letture di luce** nel thread worker: costose e rifatte per ogni faccia.
   Possono essere lette una sola volta per sezione (18³ valori).

Non sono colli di bottiglia CPU dimostrati, quindi non vanno toccati:
frustum culling per sezione, upload GPU, allocazione dei buffer GPU
(TLSF + staging già presenti), scelta del backend.

## 3. Componenti trasferibili in Rust

| Componente | Motivo |
|---|---|
| Face culling (`Block.shouldRenderFace`) | Dipende solo da proprietà per-BlockState (forme di occlusione per faccia, regola `skipRendering`) esportabili una volta in una tabella. |
| AO liscia e luce piatta (`BlockModelLighter`) | Aritmetica deterministica su stati e luce dei vicini. Port esatto, comprese le stranezze vanilla (il secondo spostamento lungo la direzione nel test `lightPermeable`, il ricorso a `shade0`/`light0` negli angoli). |
| Generazione dei vertici e separazione dei layer | Quad statici dei modelli vanilla + posizione + colore + luce, scritti nel formato `BLOCK` a 28 byte. |
| `VisGraph` | Flood fill puro. |
| Statistiche, contatori e istogrammi del profiler | Lock-free in Rust, letti da Java. |
| Scheduler parallelo con priorità (benchmark offline) | Vedi §6: in-game resta lo scheduler vanilla. |

## 4. Componenti da mantenere in Java

| Componente | Motivo |
|---|---|
| Lettura del mondo (`RenderSectionRegion`), light engine, block entity | API di Minecraft; Rust riceve una snapshot compatta. |
| Selezione delle varianti (`collectParts` con seed) | Usa il `RandomSource` di Minecraft; Java passa a Rust l'id della combinazione scelta. |
| Tinta per bioma (`BlockTintSource`) | Codice arbitrario (anche delle mod); Java calcola il colore e lo passa in tabella. |
| Offset di posizione (fiori, erba alta) | `OffsetFunction` per posizione; Java lo calcola e lo passa in tabella. |
| Fluidi (`FluidRenderer`) | Geometria dipendente da altezze e flusso; resta vanilla in questa milestone. |
| Modelli non vanilla (`BlockStateModel` delle mod, Fabric Renderer API) | Codice arbitrario: **fallback Java** per blocco. |
| Ordinamento traslucido, upload GPU, draw | Già ottimizzati e legati a `renderpearl`. |
| Autenticazione, rete, gameplay | Fuori dallo scopo; mai toccati. |

## 5. Rischi di compatibilità

| Rischio | Mitigazione |
|---|---|
| Sodium/Iris sostituiscono le stesse classi | Rilevamento all'avvio → motore disattivato, motivo registrato nel report. |
| Mod che aggiungono `BlockStateModel` o `BlockStateModelPart` propri | Allow-list di classi vanilla; il resto passa al fallback Java blocco per blocco. |
| Mod che sovrascrivono `skipRendering`, `getShadeBrightness` o forme dinamiche | Rilevamento via reflection della classe che dichiara il metodo; stati non verificabili → fallback Java (anche come vicini, dove serve). |
| Mixin di altre mod su `SectionCompiler`/`ModelBlockRenderer` | Il Compatibility Analyzer legge i config Mixin dei jar e segnala i target; Bloom usa `@WrapOperation` (si concatena con altre mod) invece di `@Overwrite`. |
| Differenze visive | Validatore integrato che compila la stessa sezione con il percorso vanilla e con Rust e confronta i quad byte per byte. |
| Crash nativo | Ogni chiamata JNI cattura i panic (`catch_unwind`) e restituisce un codice d'errore; Java disattiva il motore e ricompila in vanilla. Un segfault non è recuperabile: per questo il codice Rust esegue solo accessi con bounds check su buffer di dimensione validata. |
| Account, multiplayer | Il motore non tocca classi di rete né di autenticazione. |

## 6. Strategia di integrazione

1. **Loader:** Fabric (l'unico realistico per 26.3, con versioni stabili di
   Loader e Loom). Forge/NeoForge: nessuna integrazione parziale in questa
   milestone. Il launcher Bloom riceve un `LoaderAdapter` Fabric reale.
2. **Punto d'aggancio:** `SectionCompiler.compile`.
   - All'ingresso: Java costruisce una snapshot (stati 20³, luce 18³, id dei
     modelli 16³, tabelle di tinta e offset) in un buffer off-heap per thread
     e fa **una** chiamata JNI per sezione.
   - Rust produce i vertici per layer, il `VisibilitySet` e la lista dei
     blocchi da delegare a Java.
   - `@WrapOperation` su `ModelBlockRenderer.tesselateBlock`: salta i blocchi
     già prodotti da Rust e lascia a vanilla quelli di fallback.
   - Prima della `build()` dei layer: i vertici Rust vengono copiati in blocco
     (`memCopy`) nei `BufferBuilder` vanilla. Ordinamento traslucido, upload
     e draw restano invariati, quindi **funzionano sia con OpenGL sia con
     Vulkan** di `renderpearl`.
3. **Thread:** la chiamata Rust avviene nel worker vanilla che esegue il
   task. Priorità per distanza, cancellazione e assenza di duplicati restano
   garantite da `SectionTaskDynamicQueue`/`CompileTask`. Un pool Rust
   aggiuntivo andrebbe in competizione per gli stessi core (i worker vanilla
   sono già circa `core − 1`). Lo scheduler Rust con priorità esiste e viene
   usato dal benchmark offline; in-game si adotterà solo se una misura lo
   giustifica.
4. **Vulkan:** si usa il backend Vulkan di Minecraft
   (`preferredGraphicsBackend:vulkan`). Un backend Vulkan proprietario in
   Rust non è giustificato finché il renderer di Bloom passa da
   `renderpearl` (§1.1).
5. **Greedy meshing:** **non è compatibile** con lo shader `terrain` vanilla.
   Le UV indicano sotto-regioni di un atlante senza ripetizione: unire le
   facce stirerebbe la texture. Inoltre AO e luce sono per vertice. Sarebbe
   necessario un formato di vertice con ripetizione all'interno dello sprite
   e uno shader proprio, che romperebbero resource pack e shader pack. Non
   viene applicato in questa milestone.
6. **Fallback:** configurazione `config/bloom-engine.json` (`enabled`,
   `validate`, `diagnostics`), disattivazione automatica in caso di errore
   nativo e ripristino del percorso vanilla senza toccare la sessione.
