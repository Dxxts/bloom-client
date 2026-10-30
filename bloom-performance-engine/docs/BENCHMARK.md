# Benchmark e risultati (2026-10-30)

Tutti i numeri sono misurati su questa macchina (12 thread logici, GPU NVIDIA,
Windows 11) e salvati in `benchmarks/results/`. Dove c'è una sola esecuzione
per lato la varianza non è nota, ed è dichiarato.

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
