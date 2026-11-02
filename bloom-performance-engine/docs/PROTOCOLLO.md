# Protocollo Java ↔ Rust (versione 2)

La fonte di verità è `native/crates/bloom-core/src/protocol.rs`; la copia
Java è `java/.../bridge/Protocol.java`. Tutti i buffer sono little-endian,
off-heap (allocati con `MemoryUtil` di LWJGL), posseduti da Java e letti da
Rust solo durante la chiamata che li riceve. Nessun oggetto Java attraversa
il confine e nessuna funzione JNI usa `JNIEnv`.

Novità della versione 2 (Milestone 03): fluidi nativi (campi fluido nel
record di stato, tabella dei modelli fluidi, voci per blocco, piano esteso),
voci di offset che portano l'**origine** del blocco, statistiche dei fluidi
nel risultato. Una libreria v1 con una mod v2 (o viceversa) viene rifiutata
da `protocolVersion()` e il motore resta spento.

## Entry point JNI (`NativeBridge`)

| Funzione | Quando | Ritorno |
|---|---|---|
| `protocolVersion()` | caricamento della libreria | deve valere 2, altrimenti il motore resta spento |
| `installTables(states, pairs)` | a ogni nuovo `SectionCompiler` (reload delle risorse) | stato; sostituisce le tabelle in modo atomico e azzera i modelli |
| `registerModel(buf)` | la prima volta che compare una combinazione di parti | id ≥ 1 oppure −stato |
| `createContext()` / `destroyContext(ctx)` | uno per thread worker | handle |
| `planLight(ctx, snapshot, plan)` | per sezione, prima della luce | stato; scrive il piano (`PLAN_SIZE` = 8 932 byte) |
| `meshSection(ctx, snapshot, result)` | per sezione | stato; scrive 128 byte di risultato |
| `readStats` / `resetStats` / `lastError` / `shutdown` | profiling e fallback | |

Codici di stato: `0 OK`, `1 STALE_TABLES` (epoch superata: la sezione va a
vanilla senza errore), `2 INVALID_INPUT`, `3 PANIC` (panic catturato), `4 INTERNAL`.
Qualsiasi stato diverso da 0 e 1 disattiva il motore per la sessione.

## Flusso di una sezione

```
Java  snapshot (stati 20³, modelli 16³, tinte, origini; 0 voci fluido)
Rust  planLight → maschera della luce + elenco dei fluidi visibili (+ flag "serve il flusso")
Java  luce solo dove indicato; per ogni fluido richiesto: tinta, e flusso se richiesto
      → voci fluido in coda allo snapshot, contatore all'offset 56
Rust  meshSection → vertici per layer, VisibilitySet, blocchi e fluidi da rimandare a Java
```

## Snapshot di sezione

| Offset | Dimensione | Contenuto |
|---|---|---|
| 0 | 4 | magic `BLMS` |
| 4 | 2 | versione (2) |
| 6 | 2 | flag: bit 0 AO, bit 1 foglie cutout, bit 2 fluidi nativi |
| 8 | 4 | epoch delle tabelle |
| 12 | 4 | numero di voci di tinta |
| 16 | 4 | numero di voci di offset |
| 20 | 12 | coordinate della sezione (diagnostica) |
| 32 | 24 | `CardinalLighting` (down, up, north, south, west, east) |
| 56 | 4 | numero di voci fluido |
| 64 | 32 000 | id di stato `u32` per 20³ posizioni, da −2 a 17, indice `x + z·20 + y·400` |
| 32 064 | 5 832 | luce `sky << 4 \| block` per 18³ posizioni, da −1 a 16 (solo le posizioni della maschera) |
| 37 896 | 16 384 | slot modello `u32` per i 16³ blocchi, indice `x \| z<<4 \| y<<8`: 0 nessuno, `0xFFFFFFFF` Java, altrimenti id |
| 54 280 | 16·n | tinte: `u16` indice, `u8` numero, 1 riservato, 3 × `i32` ARGB |
| … | 16·m | origini: `u16` indice, 2 riservati, 3 × `f32` = posizione nella sezione + `getOffset`, già nell'aritmetica del renderer da riprodurre |
| … | 16·k | fluidi: `u16` indice, `u8` flag (bit 0 flusso non nullo, bit 1 flusso calcolato), 1 riservato, `i32` tinta ARGB (−1 senza tint source), `f32 s`, `f32 c` |

Origine: vanilla usa `x + (float) offset.x`; Indigo (Fabric API) usa
`(float) ((double) x + offset.x)`. Le due espressioni differiscono di un ulp
per alcuni offset, per questo Java invia l'origine e Rust non la ricalcola.

`s` e `c` sono i termini di `FluidRenderer`:
`angle = (float) Mth.atan2(flow.z, flow.x) − π/2`, `s = Mth.sin(angle) · 0.25`,
`c = Mth.cos(angle) · 0.25`, calcolati in Java con le stesse funzioni di
vanilla (le tabelle di seno di `Mth` non vengono riprodotte in Rust).

## Piano (`PLAN_SIZE` = 8 932 byte)

| Offset | Dimensione | Contenuto |
|---|---|---|
| 0 | 729 | maschera della luce, un bit per posizione 18³ |
| 736 | 4 | numero di fluidi richiesti |
| 740 | 2·k | `u16`: indice del blocco, bit 15 se la faccia superiore viene disegnata (serve il flusso) |

## Tabella degli stati (32 byte per stato, indicizzata dall'id globale)

`u32 flag` (AIR, SOLID_RENDER, LIGHT_PERMEABLE, EMISSIVE_RENDERING,
COLLISION_FULL_BLOCK, DYNAMIC, LEAVES, TINTED, **SOLID** = `isSolid()`,
**OVERLAY_NEIGHBOR** = blocco trasparente per l'overlay dei fluidi), `u32 blockId`,
`f32 shade`, `u8 lightEmission`, `u8 skipRule` (0 nessuna, 1 stesso blocco,
2 foglie, 255 codice non verificabile), `u16` classi di occlusione (2 bit per
direzione: vuota, `Shapes.block()`, parziale, parziale che copre),
`6 × u16` id delle forme parziali, **`u8` gruppo del fluido** (0 nessuno,
255 non verificato, altrimenti gruppi con `isSame` equivalente), **`u8`
modello del fluido**, **`u8` amount** (l'altezza propria è `amount / 9`,
verificata in Java su ogni stato), 1 riservato.

Dopo i record, facoltativa, la sezione dei modelli fluidi: magic `BLFM`,
`u32` numero, record da 64 byte (`u8` layer, `u8` flag: bit 0 tinta, bit 1
overlay, 2 riservati, poi sprite still, flowing, overlay come
`(u0, u1, v0, v1)` in `f32`).

La tabella delle coppie di forme contiene un bit per coppia
`joinIsNotEmpty(a, b, ONLY_FIRST)`. Viene costruita solo se le forme distinte
sono al massimo 512; altrimenti le facce parziale-contro-parziale tornano a
Java.

## Modello (32 byte di header + 96 per quad)

Header: magic `BLMD`, versione, epoch, numero di quad, flag (bit 0 = AO della
prima parte). Quad: 4 × posizione (`f32`), 4 × UV (`f32`), `i32 tintIndex`,
`u8` direzione, `u8` faccia di culling (6 = nessuna), `u8` layer, `u8` override
d'ombra (255 = nessuno), `u8` emissione. L'ordine dei quad è quello vanilla:
parte per parte, direzioni `DOWN..EAST`, poi i quad senza culling.

## Risultato (128 byte)

`i32` stato, `u32` voci da rimandare a Java, `u64` puntatore alla loro
lista (`u16`: indice del blocco, **bit 15 se è il fluido** e non il modello),
poi per i layer SOLID/CUTOUT/TRANSLUCENT `u64` puntatore e `u32` vertici,
`u64` `VisibilitySet` (layout del `BitSet` vanilla), poi statistiche: `u32`
blocchi nativi (72), quad (76), facce scartate (80), blocchi retrocessi (84),
`u64` ns (88), **`u32` fluidi nativi (96), fluidi retrocessi (100)**.
I puntatori restano validi fino alla chiamata successiva sullo stesso
contesto; Java copia i vertici con un `memCopy` nei `BufferBuilder` vanilla.
