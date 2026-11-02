# Benchmark e risultati

Tutti i numeri sono misurati su questa macchina (i5-10400F, 12 thread
logici, GTX 1660 SUPER, Windows 11) e salvati in `benchmarks/results/`.
Dove c'è una sola esecuzione per lato la varianza non è nota, ed è dichiarato.

# Milestone 03 (2026-11-01)

## A. In gioco: renderer originale, Bloom OpenGL, Bloom Vulkan

`scripts/run-benchmark.ps1 -Repeat 3`: mondo "New World" (copia fresca a ogni
run), distanza 16, VSync off, FPS illimitati, 10 s di riscaldamento,
ricompilazione di tutte le sezioni, rotazione di 360° in 30 s. Tre ripetizioni
per configurazione, **in ordine alternato** (ogni giro esegue le quattro
configurazioni). Mediana delle tre. File: `benchmarks/results/m03-ingame/`.

| Metrica (mediana di 3) | Vanilla OpenGL | **Bloom OpenGL** | Vanilla Vulkan | **Bloom Vulkan** |
|---|---|---|---|---|
| Compilazione sezione, media | 9,92 ms | **5,74 ms (−42%)** | 8,25 ms | **4,32 ms (−48%)** |
| Compilazione sezione, mediana | 5,50 ms | **3,49 ms (−37%)** | 5,30 ms | **2,93 ms (−45%)** |
| Compilazione sezione, p99 | 54,4 ms | 56,6 ms | 57,9 ms | **37,0 ms** |
| Ricompilazione di tutte le sezioni visibili (tempo reale) | 506 ms | 504 ms | 611 ms | 526 ms |
| FPS medi in rotazione | 334 | 385 | 473 | 442 |
| 1% low | 59 | 70 | 109 | 98 |
| Frame time p99 | 6,92 ms | 6,36 ms | 6,59 ms | 6,96 ms |
| Tempo GPU per frame (timestamp) | 3,00 ms | 2,60 ms | 2,12 ms | 2,27 ms |
| Draw call per frame | 37,9 | 30,8 | 30,1 | 30,2 |
| Entità + block entity + particelle (CPU, per frame) | 0,26 ms | 0,20 ms | 0,23 ms | 0,23 ms |
| VRAM | 1 943 MiB¹ | 1 895 MiB¹ | 576 MiB² | 576 MiB² |

¹ `GL_NVX_gpu_memory_info`: tutta la GPU, comprese le altre applicazioni.
² Budget VMA dell'allocatore di Minecraft. Le due misure non sono confrontabili.

Valori per ripetizione (FPS medi; media di compilazione):

| Giro | Vanilla GL | Bloom GL | Vanilla VK | Bloom VK |
|---|---|---|---|---|
| 1 | 237; 9,92 | 369; 16,92 | 270; 10,19 | 339; 4,48 |
| 2 | 334; 13,74 | 465; 5,74 | 542; 6,64 | 442; 4,32 |
| 3 | 386; 6,81 | 385; 4,64 | 473; 8,25 | 508; 3,92 |

Cosa dicono questi numeri:

- **La compilazione delle sezioni è il guadagno reale e ripetibile**: su
  Vulkan Bloom è più veloce in tutti e tre i giri (media −48%, mediana −45%,
  p99 −36%). Su OpenGL in due giri su tre; il primo giro Bloom/OpenGL ha una
  media anomala (16,9 ms) con mediana ancora più alta, dovuta al primo avvio
  della sessione di misura.
- **Gli FPS non sono distinguibili**: la stessa configurazione varia fino al
  100% tra un giro e l'altro (vanilla Vulkan 270 → 542). Il primo giro è più
  lento per tutti. Bloom disegna la stessa mesh di vanilla, quindi non c'è
  motivo di attendersi un guadagno a scena ferma, e non va dichiarato.
- Il tempo di ricompilazione dell'intera visuale è limitato da altro (coda,
  upload, numero di worker), non dal costo della singola sezione.
- Entità, block entity e particelle costano circa 0,2 ms per frame su
  questo mondo (7–8 entità visibili): è la ragione misurata per non portarle
  in Rust (vedi [M03_ANALISI.md](M03_ANALISI.md)).

## B. Fluidi nativi (Bloom Vulkan, tre ripetizioni per lato)

Stesso protocollo, `-Engines bloom -Backends vulkan -Fluids false` contro le
tre ripetizioni Bloom Vulkan della tabella A. Il mondo ha circa 200 000
blocchi di fluido nelle sezioni ricompilate.

| Metrica (mediana di 3) | Fluidi in Java | Fluidi in Rust |
|---|---|---|
| Compilazione sezione, media | 4,51 ms | 4,32 ms (−4%) |
| Compilazione sezione, mediana | 3,29 ms | 2,93 ms (−11%) |
| Compilazione sezione, p99 | 28,6 ms | 37,0 ms |
| Snapshot Java (media) | 2,14 ms | 3,00 ms |
| Mesh Rust (media) | 0,71 ms | 0,82 ms |

La mediana migliora in tutti i giri (3,26/3,29/3,51 contro 2,93/2,89/3,26 ms),
ma il guadagno è piccolo su un mondo di terraferma: la tassellazione dei
fluidi esce dal ciclo Java, mentre la snapshot cresce (tinte, flusso, piano
dei fluidi). Il p99 non migliora. In una scena di oceano il mesher Rust
tratta 1,65 milioni di blocchi di fluido a 94 µs per sezione (offline, C);
il confronto in gioco su un mondo oceanico non è stato fatto.

## C. Mesher offline (`bloom-bench`, Rust da solo)

`bloom-bench --sections-x 16 --runs 5`, quattro scene, acqua ora come
fluido nativo (nella M02 era rimandata a Java, quindi le scene non sono
confrontabili con la tabella M02). File: `native-mesher-2026-11-01-fluids.json`.

| Scena | Quad | Fluidi nativi | µs/sezione (mesh) | µs/sezione (piano + mesh) | Sezioni/s, 11 thread | Allocazioni/sezione |
|---|---|---|---|---|---|---|
| Terreno naturale | 396 166 | 6 217 | 142,7 | 198,4 | 40 370 | 0 |
| Grandi costruzioni | 1 065 537 | 0 | 223,8 | 256,2 | 26 476 | 0 |
| Blocchi trasparenti | 12 952 267 | 458 875 | 1 812,9 | 2 014,7 | 3 163 | 0 |
| Oceano | 219 350 | 1 654 305 | 94,5 | 157,9 | 63 006 | 0 |

Correzione emersa: la M02 dichiarava 0 allocazioni per sezione, ma il codice
creava un `Box` a ogni sezione (probabilmente eliminato dall'ottimizzatore
prima). Ora la maschera viene presa e restituita senza allocare.

## D. Backend Vulkan sperimentale (`bloom-gpu-bench`)

Vedi [VULKAN.md](VULKAN.md): 1 024 sezioni, 1080p offscreen, GPU 0,50–0,55 ms
per frame sul terreno; il multi-draw indirect porta le draw call da 511 a 1
e il tempo CPU di registrazione da 0,029 a 0,013 ms.

## E. Non misurato

Modpack pesanti, scene con molte entità, GPU AMD/Intel, mondo oceanico in
gioco, più di tre ripetizioni per configurazione.

# Milestone 02 (2026-10-30)

## 1. Mesher nativo offline (`bloom-bench`)

Scene sintetiche deterministiche (1024 sezioni ciascuna, 5 ripetizioni),
codificate nello stesso formato scritto da Java e meshate dallo stesso entry
point della JNI. Misura **solo Rust**: non è un confronto con vanilla.

```
D:\bloom-build\target\release\bloom-bench.exe --sections-x 16 --runs 5 --out benchmarks/results/<file>.json
```

| Scena | Quad | µs/sezione (media) | p95 µs | Sezioni/s, 1 thread | Sezioni/s, 11 thread | Speedup | Allocazioni/sezione (contesto riusato / nuovo) |
|---|---|---|---|---|---|---|---|
| Terreno naturale | 388 114 | 201,4 | 639,4 | 4 966 | 25 419 | 5,1× | 0 / 10,9 |
| Grandi costruzioni | 1 065 537 | 334,9 | 1 048,7 | 2 986 | 16 175 | 5,4× | 0 / 13,9 |
| Blocchi trasparenti | 8 629 347 | 2 038,5 | 3 711,4 | 491 | 2 963 | 6,0× | 0 / 40,0 |

Ottimizzazione misurata, scrittura dei vertici in un blocco da 112 byte
per quad: blocchi trasparenti da 2 518,5 a 2 038,5 µs (−19%). Terreno
(192,8 → 201,4) e costruzioni (342,7 → 334,9) restano nel rumore.

## 2. In gioco, A/B (`scripts/run-benchmark.ps1`)

Stesso mondo (copia fresca per ogni esecuzione), distanza 16, VSync off, FPS
illimitati, backend `default` (OpenGL), validatore spento. Procedura: 10 s di
riscaldamento, ricompilazione di tutte le sezioni (F3+A), poi rotazione di 360°
della camera in 30 s. **Una sola esecuzione per lato.**

| Metrica | Vanilla | Bloom Native | Differenza |
|---|---|---|---|
| Compilazione sezione, media | 6,14 ms | 4,98 ms | **−19%** |
| Compilazione sezione, mediana | 4,40 ms | 3,35 ms | **−24%** |
| Compilazione sezione, p95 | 16,56 ms | 16,21 ms | −2% |
| Compilazione sezione, p99 | 34,64 ms | 46,52 ms | **+34% (peggio)** |
| Compilazione sezione, max | 206,2 ms | 71,5 ms | −65% |
| Ricompilazione delle sezioni visibili (tempo reale) | 576 ms | 558 ms | −3% (nel rumore) |
| FPS medi in rotazione | 483 | 463 | −4% (non distinguibile dal rumore con 1 esecuzione) |
| 1% low in rotazione | 115,5 | 107,7 | −7% (idem) |
| Frame time p99 | 6,11 ms | 6,20 ms | +1% |
| Stutter (> 2,5× mediana e > 33 ms) | 3 | 2 | — |

Scomposizione del percorso nativo (stessa esecuzione): snapshot Java
**2,32 ms** di media, mesh Rust **0,73 ms** di media. Blocchi nativi
1 703 081, rimandati a Java 161.

Costruzione delle tabelle a ogni ingresso nel mondo/reload: 608 ms → **188 ms**
dopo aver messo in cache la copertura per forma (una misura per lato).

Correttezza: nella prima sessione il validatore ha ricompilato con vanilla
una sezione su 4 (823 sezioni native): **0 differenze** nei quad e nei
`VisibilitySet`.

## 3. Cosa dicono questi numeri

- Il mesher Rust riduce il costo medio di compilazione di una sezione di circa
  un quinto. I casi peggiori (p99) però peggiorano: la snapshot Java ha code
  lunghe (p99 41 ms), che vanno ridotte prima di qualsiasi altra cosa.
- Gli FPS in gioco **non migliorano**: la mesh prodotta è identica a quella
  vanilla, quindi la GPU disegna esattamente la stessa cosa. Il guadagno
  atteso è in caricamento e aggiornamento dei chunk, non nel frame rate a
  scena ferma.
- Il prossimo collo di bottiglia misurato è la snapshot Java (circa 3/4 del
  percorso nativo).

## 4. Non misurato

GPU time, VRAM, draw call (nessuna sorgente portabile in 26.3), backend
Vulkan, modpack pesanti, scene con molte entità o block entity. Gli script
sono pronti; queste misure vanno eseguite con più ripetizioni per lato.
