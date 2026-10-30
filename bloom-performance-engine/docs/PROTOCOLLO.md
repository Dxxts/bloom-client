# Protocollo Java ↔ Rust (versione 1)

La fonte di verità è `native/crates/bloom-core/src/protocol.rs`; la copia
Java è `java/.../bridge/Protocol.java`. Tutti i buffer sono little-endian,
off-heap (allocati con `MemoryUtil` di LWJGL), posseduti da Java e letti da
Rust solo durante la chiamata che li riceve. Nessun oggetto Java attraversa
il confine e nessuna funzione JNI usa `JNIEnv`.

## Entry point JNI (`NativeBridge`)

| Funzione | Quando | Ritorno |
|---|---|---|
| `protocolVersion()` | caricamento della libreria | deve valere 1, altrimenti il motore resta spento |
| `installTables(states, pairs)` | a ogni nuovo `SectionCompiler` (reload delle risorse) | stato; sostituisce le tabelle in modo atomico e azzera i modelli |
| `registerModel(buf)` | la prima volta che compare una combinazione di parti | id ≥ 1 oppure −stato |
| `createContext()` / `destroyContext(ctx)` | uno per thread worker | handle |
| `planLight(ctx, snapshot, mask)` | per sezione, prima della luce | stato; scrive 729 byte di maschera |
| `meshSection(ctx, snapshot, result)` | per sezione | stato; scrive 128 byte di risultato |
| `readStats` / `resetStats` / `lastError` / `shutdown` | profiling e fallback | |

Codici di stato: `0 OK`, `1 STALE_TABLES` (epoch superata: la sezione va a
vanilla senza errore), `2 INVALID_INPUT`, `3 PANIC` (panic catturato), `4 INTERNAL`.
Qualsiasi stato diverso da 0 e 1 disattiva il motore per la sessione.

## Snapshot di sezione

| Offset | Dimensione | Contenuto |
|---|---|---|
| 0 | 4 | magic `BLMS` |
| 4 | 2 | versione (1) |
| 6 | 2 | flag: bit 0 AO, bit 1 foglie cutout |
| 8 | 4 | epoch delle tabelle |
| 12 | 4 | numero di voci di tinta |
| 16 | 4 | numero di voci di offset |
| 20 | 12 | coordinate della sezione (diagnostica) |
| 32 | 24 | `CardinalLighting` (down, up, north, south, west, east) |
| 64 | 32 000 | id di stato `u32` per 20³ posizioni, da −2 a 17, indice `x + z·20 + y·400` |
| 32 064 | 5 832 | luce `sky << 4 \| block` per 18³ posizioni, da −1 a 16 (solo le posizioni della maschera) |
| 37 896 | 16 384 | slot modello `u32` per i 16³ blocchi, indice `x \| z<<4 \| y<<8`: 0 nessuno, `0xFFFFFFFF` Java, altrimenti id |
| 54 280 | 16·n | tinte: `u16` indice, `u8` numero, 1 riservato, 3 × `i32` ARGB |
| … | 16·m | offset: `u16` indice, 2 riservati, 3 × `f32` |

Il bordo di 2 blocchi esiste perché l'AO vanilla legge lo stato a
`pos + 2·direzione + angolo` (la `move(direction)` dopo l'offset d'angolo).

## Tabella degli stati (32 byte per stato, indicizzata dall'id globale)

`u32 flag` (AIR, SOLID_RENDER, LIGHT_PERMEABLE, EMISSIVE_RENDERING,
COLLISION_FULL_BLOCK, DYNAMIC, LEAVES, TINTED), `u32 blockId`, `f32 shade`,
`u8 lightEmission`, `u8 skipRule` (0 nessuna, 1 stesso blocco, 2 foglie, 255
codice non verificabile), `u16` classi di occlusione (2 bit per direzione:
vuota, `Shapes.block()`, parziale, parziale che copre), `6 × u16` id delle
forme parziali.

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

`i32` stato, `u32` blocchi da rimandare a Java, `u64` puntatore alla loro
lista (`u16`), poi per i layer SOLID/CUTOUT/TRANSLUCENT `u64` puntatore e `u32`
vertici, `u64` `VisibilitySet` (layout del `BitSet` vanilla), statistiche.
I puntatori restano validi fino alla chiamata successiva sullo stesso
contesto; Java copia i vertici con un `memCopy` nei `BufferBuilder` vanilla.
