# Bloom Native World Engine e Bloom Client in gioco

Chunk mesher nativo (Rust) per Minecraft 26.3 su Fabric, con fallback
verificato sul renderer vanilla, fluidi nativi, compatibility layer con
database firmato, F3 proprietario e un backend Vulkan sperimentale.
Dalla Milestone 05 lo stesso jar contiene anche Bloom Client in gioco:
Bloom Menu (Right Shift), HUD Studio, framework Bloom Mods e le mod
ufficiali World Tracker e Inventory Manager.

Documentazione:

- [docs/M05_STATO.md](docs/M05_STATO.md): **stato della Milestone 05** (menu, HUD Studio, mod)
- [docs/BLOOM_MODS.md](docs/BLOOM_MODS.md): architettura di Bloom Client in gioco e guida alle Bloom Mods (lifecycle, eventi, configurazioni, persistenza, widget HUD, esempi)

- [docs/M03_STATO.md](docs/M03_STATO.md): **cosa è completo, sperimentale, non implementato** (Milestone 03)
- [docs/M03_ANALISI.md](docs/M03_ANALISI.md): analisi dell'esistente e decisioni della M03
- [docs/ANALISI_TECNICA.md](docs/ANALISI_TECNICA.md): analisi del renderer di 26.3 (M02)
- [docs/ARCHITETTURA.md](docs/ARCHITETTURA.md): cosa è in Rust e cosa in Java, flusso, fallback
- [docs/PROTOCOLLO.md](docs/PROTOCOLLO.md): protocollo binario Java ↔ Rust v2
- [docs/COMPATIBILITA.md](docs/COMPATIBILITA.md): compatibility layer, adapter Fabric API, shader, mod verificate
- [docs/DATABASE.md](docs/DATABASE.md): database di compatibilità firmato e aggiornabile
- [docs/VULKAN.md](docs/VULKAN.md): Vulkan in gioco e backend proprietario sperimentale
- [docs/DIAGNOSTICA.md](docs/DIAGNOSTICA.md): voci F3 Bloom e metriche
- [docs/BENCHMARK.md](docs/BENCHMARK.md): risultati misurati

## Compilazione

Requisiti: Rust (MSVC), JDK 25+, rete per il primo download di Gradle,
Minecraft e dei crate Vulkan. Le uscite vanno su `D:\bloom-build` perché C:
ha poco spazio.

```powershell
.\scripts\build-native.ps1        # cargo test + build release + gradle build (con test JUnit)
```

Uscite: `D:\bloom-build\target\release\bloom_native.dll`,
`bloom-bench.exe`, `bloom-gpu-bench.exe` e
`java\build\libs\bloom-engine-0.3.0.jar` (contiene la DLL, il database firmato e Bloom Client in gioco).

## Provare in sviluppo

```powershell
cd java; $env:GRADLE_USER_HOME="D:\bloom-build\gradle-home"
.\gradlew runClient                                                    # gioco con il motore
..\scripts\run-benchmark.ps1 -World "New World" -Repeat 3              # vanilla/Bloom × OpenGL/Vulkan
bash ../scripts/run-compat-test.sh fabric-api vulkan <jar>             # prova di compatibilità
D:\bloom-build\target\release\bloom-gpu-bench.exe --scene terrain      # backend Vulkan sperimentale
```

Impostazioni del motore: nel launcher, **Modifica istanza → Bloom Engine**
(visibile per le istanze "Bloom Native · Rust"): rendering nativo sì/no,
acqua e lava native, verifica con il renderer vanilla, mod con agganci non
verificati, GPU Vulkan. Vengono salvate per istanza nel database del launcher
e passate al gioco come `-Dbloom.engine.*` / `-Dbloom.vulkan.device`, che
hanno la precedenza sul file. `config/bloom-engine.json` resta solo per
l'avvio senza launcher (sviluppo) e per `modModes`:

| Chiave | Predefinito | Effetto |
|---|---|---|
| `enabled` | `true` | `false`: puro vanilla |
| `nativeFluids` | `true` | Acqua e lava in Rust |
| `validateEvery` | `0` | Confronta con vanilla 1 sezione ogni N (0 = spento; non lasciarlo acceso giocando) |
| `allowUnverified` | `false` | Tiene il motore acceso con mod che agganciano il terreno senza verifica |
| `modModes` | `{}` | Modalità per mod: `NATIVE_ADAPTER`, `AUTOMATIC_ADAPTER`, `JAVA_FALLBACK`, `ENGINE_OFF` |
| `vulkanDevice` | `""` | GPU per il backend Vulkan di Minecraft (indice o parte del nome) |
| `statsIntervalSeconds` | `30` | Intervallo di `.bloom/engine-stats.json` |

Le chiavi `enabled`, `nativeFluids`, `validateEvery`, `allowUnverified` e
`vulkanDevice` arrivano dal launcher quando l'istanza parte da lì. Con Fabric API serve
`ambient-occlusion-mode=vanilla` in `config/fabric/indigo-renderer.properties`
(vedi [COMPATIBILITA.md](docs/COMPATIBILITA.md)).

## Integrazione con Bloom Client

Nel launcher, la selezione della versione ha due gruppi separati:
**Bloom Native · Rust** (solo le versioni supportate) e
**Java · Renderer vanilla**. Un'istanza Rust usa Fabric in automatico e il jar
del motore viene aggiunto con `-Dfabric.addMods`, senza toccare la cartella
`mods`. Il launcher aggiorna il database di compatibilità all'avvio (verifica
SHA-256 e firma Ed25519) e lo passa con `-Dbloom.compatDb`; con Iris
installato sceglie OpenGL, perché sul backend Vulkan Iris non applica gli
shaderpack. `scripts\build-windows.ps1` compila il motore e lo include
nell'installer (`resources/engine/bloom-engine.jar`).
