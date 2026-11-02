# Milestone 03: analisi dell'esistente e piano (Fase 1)

Data: 2026-11-01. Fonti: questo repository (commit `e72ac07`), build e test
eseguiti oggi, il jar client di Minecraft 26.3 decompilato
(`D:\bloom-build\mc-26.3\src*`), l'API di Modrinth.

## 1. Stato reale delle milestone precedenti

### Milestone 01 (launcher)

Tauri 2 + React. Account Microsoft (bloccato lato Mojang finché l'app Azure
non è approvata), istanze, download, Java gestito, aggiornamenti. Il modulo
`src-tauri/src/engine` aggiunge il motore alle istanze "Bloom Native" con
`-Dfabric.addMods` e imposta `preferredGraphicsBackend:"vulkan"` se il
giocatore non ha scelto (una scelta esplicita `opengl` resta). Nessuna
interfaccia per mod, compatibilità o diagnostica del renderer.

### Milestone 02.1 (Bloom Native World Engine)

| Componente | Stato verificato oggi |
|---|---|
| Workspace Rust (7 crate, zero dipendenze esterne) | `cargo test --workspace`: **45 test, tutti verdi** |
| Mod Fabric (Loom 1.19.3, MC 26.3, Java 25) | `gradlew build`: **compila**; **0 test Java** |
| Mesher nativo dei blocchi a modello statico | Implementato, validato in gioco (0 differenze su 823 sezioni) |
| Fluidi, block entity, modelli non statici | Restano interamente in Java (vanilla) |
| Entità, particelle | Non toccate, non misurate |
| Shader | Nessuna gestione. Sodium/Iris → motore spento |
| Vulkan | Nessun codice Bloom. Si usa il backend Vulkan di Minecraft (`renderpearl`) |
| Compatibilità | Analizzatore statico (mixin, API, modelli) con 4 verdetti; API adapter statici |
| Database di compatibilità | **Non esiste** |
| F3 proprietario | **Non esiste**: le statistiche vanno solo in `.bloom/engine-stats.json` e nel log |
| GPU time, VRAM, draw call | **Non misurati** (dichiarato in `BENCHMARK.md`) |
| Benchmark in gioco | Uno script A/B (vanilla vs Bloom, OpenGL), una sola esecuzione per lato |

Le affermazioni della documentazione M02 corrispondono al codice: non ho
trovato funzionalità dichiarate ma assenti.

## 2. Fatti di Minecraft 26.3 che vincolano la M03

1. **Vulkan c'è già.** `com.mojang.renderpearl` ha un frontend comune
   (`FrontendGpuDevice`, `FrontendRenderPass`) e due backend: OpenGL e
   Vulkan (VMA, shaderc, coda di distruzione differita, checkpoint per i
   device lost). La scelta del device fisico (`VulkanBackend.findPhysicalDevice`)
   prende il primo device adatto e preferisce una GPU discreta: **non c'è
   modo di scegliere la GPU**.
2. **Timestamp GPU disponibili su entrambi i backend:**
   `GpuDevice.createTimestampQueryPool`, `RenderPass.writeTimestamp`,
   `DeviceInfo.timestampPeriod`.
3. **Tutte le draw call passano da `FrontendRenderPass`** (`drawIndexed`,
   `multiDrawIndexed`, `drawIndexedIndirect`, `drawMultipleIndexed`, `draw`,
   `multiDraw`, `drawIndirect`): un solo punto per contarle su OpenGL e Vulkan.
4. **F3 è estendibile.** `DebugScreenEntries` è un registro
   `Identifier → DebugScreenEntry`; la schermata delle opzioni di debug
   elenca tutte le voci registrate. Registrando voci `bloom:*` si ottiene un
   F3 proprietario che l'utente attiva e disattiva come quelle vanilla.
5. **Entità: estrazione e submit sono già separati.** `LevelRenderer.render`
   esegue `submitFeatures` (entità, block entity, particelle → nodi di
   submit), poi `FeatureRenderDispatcher.prepareFrame`, poi il frame graph.
   Il lavoro per entità è logica Java (stati di render, animazioni,
   interpolazione) seguita da batching già fatto da Mojang.
6. **Fluidi.** `FluidRenderer.tesselate` dipende da: stati dei 6 vicini e
   dei 4 angoli (e di quelli sopra), altezza propria `amount / 9`, forme di
   occlusione, `isSolid`, `isSolidRender`, classe del blocco vicino
   (`HalfTransparentBlock`/`LeavesBlock` per l'overlay), luce, tinta per
   bioma e vettore di flusso. Tutto è funzione dello stato tranne **tinta**
   e **flusso**: sono gli unici due valori che Java deve calcolare.
7. **Shader.** Iris 1.11.7 per 26.3 richiede Sodium. Sodium sostituisce
   `SectionRenderDispatcher`/`SectionCompiler`: con Sodium il mesher Bloom
   non ha nulla da agganciare. Le core shader vanilla (resource pack) e i
   `PostChain` funzionano su dati di vertice identici a quelli vanilla.

## 3. Decisioni tecniche (con motivazione)

| Richiesta | Decisione | Motivo |
|---|---|---|
| Block rendering esteso | **Fluidi nativi in Rust** (port esatto di `FluidRenderer`), protocollo v2. Modelli dinamici, renderer Java delle mod e block entity: **fallback Java per blocco**, con adapter registrabili | I fluidi sono la parte più grande del lavoro Java rimasto nelle sezioni (oceani, fiumi). Il resto è codice arbitrario: tradurlo violerebbe "nessuna traduzione arbitraria" |
| Entity rendering | **Resta in Java.** Bloom misura submit di entità, block entity, particelle e preparazione delle feature | Logica, animazioni e interpolazioni sono codice Java delle entità e delle mod; Mojang ha già separato estrazione e batching. Spostare in Rust richiederebbe una copia per frame dello stato di ogni entità: nessun vantaggio dimostrabile prima di avere misure |
| Shader | Rilevamento di Iris/pacchetti shader/core shader nei resource pack, politica di fallback (motore spento con Sodium/Iris, OpenGL consigliato con Iris), stato in F3. **Nessun loader di shaderpack proprietario** | Iris è l'implementazione di riferimento e dipende da Sodium; riscriverla non è nello scopo di una milestone ed è dichiarato non implementato |
| Backend Vulkan | (a) **In gioco**: Bloom Vulkan = backend Vulkan di `renderpearl` + **selezione del device** Bloom + diagnostica + timestamp GPU. (b) **Crate `bloom-vulkan`** (sperimentale, fuori dal gioco): device selection, swapchain, command buffer, descriptor, allocatore, pipeline, compilazione shader, sincronizzazione, lifetime, diagnostica; usato da un benchmark GPU offline sulle mesh del mesher Bloom | Un secondo `VkDevice` in gioco non può condividere risorse con quello di `renderpearl` senza interoperabilità esplicita. Il backend proprietario va quindi tenuto separato finché non sostituisce l'intero frame; OpenGL resta il backend alternativo e Vulkan non è obbligatorio |
| Compatibility layer | Tre modalità per mod (Native Adapter / Automatic Adapter / Java Fallback) decise da analisi + database + override dell'utente; reversibili da configurazione | Le trasformazioni restano le stesse dei mixin già validati: si cambia solo **cosa** va al percorso nativo |
| Database | JSON firmato **Ed25519** + SHA-256, copia inclusa nel jar, aggiornamento scaricato dal launcher e riverificato dalla mod | Aggiornabile senza aggiornare il launcher; integrità verificata due volte; Ed25519 è nel JDK (nessuna dipendenza in Java) |
| Diagnostica | Voci F3 `bloom:*` registrate in `DebugScreenEntries` | Si integra con il sistema F3 di 26.3 invece di disegnare un overlay separato |
| Misure | Draw call da `FrontendRenderPass`, tempo GPU da timestamp `renderpearl`, tempi di submit delle entità, VRAM dove il backend la espone | Tutte le metriche richieste, sugli stessi punti per OpenGL e Vulkan |

## 4. Rischi

| Rischio | Mitigazione |
|---|---|
| Port dei fluidi non identico a vanilla | Stesso validatore della M02 (multiset di quad); fluidi disattivabili da config (`nativeFluids`); casi non riproducibili (forme parziali, fluidi delle mod) restano a Java per blocco |
| Mixin su `FrontendRenderPass`/`LevelRenderer` in conflitto con altre mod | Solo `@Inject` di misura, nessuna modifica del comportamento; disattivabili (`gpuProfiling`) |
| Scelta del device Vulkan errata | Attiva solo con una preferenza esplicita; se il device scelto non è adatto si torna alla scelta vanilla |
| Database manomesso o corrotto | Firma non valida → si usa la copia inclusa nel jar; il database non spegne mai mod, cambia solo la modalità del motore |
| Numeri di benchmark con varianza ignota | Più esecuzioni per configurazione dove possibile, dichiarate |

## 5. Ordine di lavoro

1. Analisi (questo documento) · 2. Fluidi nativi + adapter dei blocchi ·
3. Misure delle entità · 4. Compatibility layer · 5. Shader ·
6. Vulkan (in gioco + crate) · 7. Database · 8. F3 e profiling ·
9. Benchmark · 10. Test di regressione.
