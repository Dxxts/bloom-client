# Bloom Native World Engine

Chunk mesher nativo (Rust) per Minecraft 26.3 su Fabric, con fallback
verificato sul renderer vanilla. Documentazione:

- [docs/ANALISI_TECNICA.md](docs/ANALISI_TECNICA.md): analisi del renderer di 26.3 (Fase 1)
- [docs/ARCHITETTURA.md](docs/ARCHITETTURA.md): cosa è in Rust e cosa in Java, flusso, fallback
- [docs/PROTOCOLLO.md](docs/PROTOCOLLO.md): protocollo binario Java ↔ Rust v1
- [docs/COMPATIBILITA.md](docs/COMPATIBILITA.md): adapter, analizzatore, mod verificate
- [docs/BENCHMARK.md](docs/BENCHMARK.md): risultati misurati

## Compilazione

Requisiti: Rust (MSVC), JDK 25+, rete per il primo download di Gradle e
Minecraft. Le uscite vanno su `D:\bloom-build` perché C: ha poco spazio.

```powershell
.\scripts\build-native.ps1        # cargo test + build release + gradle build
```

Uscite: `D:\bloom-build\target\release\bloom_native.dll` e
`java\build\libs\bloom-engine-0.1.0.jar` (che contiene la DLL).

## Provare in sviluppo

```powershell
cd java; $env:GRADLE_USER_HOME="D:\bloom-build\gradle-home"
.\gradlew runClient                                   # gioco con il motore
..\scripts\run-benchmark.ps1 -World "New World"       # A/B vanilla vs Bloom
```

`config/bloom-engine.json`: `enabled`, `validateEvery` (confronto con vanilla
di 1 sezione ogni N), `allowUnverified`, `statsIntervalSeconds`. Statistiche in
`.bloom/engine-stats.json`, compatibilità in `.bloom/compatibility-report.json`.

## Integrazione con Bloom Client

Nel launcher, la selezione della versione ha due gruppi separati:
**Bloom Native · Rust** (icona Rust, solo le versioni supportate) e
**Java · Renderer vanilla** (icona Java). Un'istanza Rust usa Fabric in
automatico (adapter Fabric reale da Fabric Meta) e il jar del motore viene
aggiunto con `-Dfabric.addMods`, senza toccare la cartella `mods`.
`scripts\build-windows.ps1` compila il motore e lo include nell'installer
(`resources/engine/bloom-engine.jar`). In sviluppo il launcher trova il jar
in `bloom-performance-engine/java/build/libs`, oppure tramite `BLOOM_ENGINE_JAR`.
