# Database di compatibilità

`compat-db/bloom-compat-db.json`: una voce per mod (e intervallo di versioni),
firmata Ed25519. Il jar del motore ne include una copia; il launcher scarica
le versioni successive senza bisogno di un aggiornamento del launcher.

## Formato (schema 1)

```json
{
  "schema": 1,
  "sequence": 2,
  "generated": "2026-11-01",
  "entries": [
    {
      "modId": "fabric-renderer-api-v1",
      "versions": "17.0.17",
      "loader": "fabric",
      "minecraft": "26.3",
      "status": "NATIVE_ADAPTER",
      "adapterVersion": 1,
      "knownIssues": ["..."],
      "renderingFeatures": ["..."],
      "vulkan": "SUPPORTED",
      "terrainHooksVerified": true,
      "namespaces": [],
      "staticModelClasses": [],
      "tests": [{"date": "2026-11-01", "bloomVersion": "0.2.0", "backend": "vulkan", "result": "PASS", "notes": "..."}]
    }
  ]
}
```

| Campo | Significato |
|---|---|
| `modId`, `versions`, `loader`, `minecraft` | A chi si applica. `versions` e `minecraft` sono predicati di versione Fabric (`"17.0.17"`, `">=2.0.0 <3"`, `"*"`). Vince la prima voce che corrisponde |
| `status` | `NATIVE_ADAPTER`, `AUTOMATIC_ADAPTER`, `JAVA_FALLBACK`, `ENGINE_OFF`, `UNTESTED` (lascia decidere l'analisi) |
| `adapterVersion` | Versione dell'adapter Bloom con cui la voce è stata provata |
| `knownIssues`, `renderingFeatures` | Testo per il report e per il launcher |
| `vulkan` | `SUPPORTED`, `UNSUPPORTED`, `UNTESTED` sul backend Vulkan di Minecraft |
| `terrainHooksVerified` | La mod aggancia classi del terreno e la combinazione è stata validata in gioco. Senza, una voce non può accendere il motore per quella mod |
| `namespaces` | Namespace dei blocchi interessati da `JAVA_FALLBACK` (predefinito: il mod id) |
| `staticModelClasses` | Classi di modello da trattare come statiche (`NATIVE_ADAPTER`). Classi inesistenti o che non sono modelli vengono ignorate |
| `tests` | Prove reali: data, versione di Bloom, backend, esito, note |

`sequence` cresce a ogni pubblicazione: una copia firmata ma più vecchia non
sostituisce mai una più nuova (protezione dal rollback).

## Integrità

- **Firma**: Ed25519 sui byte esatti del file (`bloom-compat-db.json.sig`,
  base64). Chiave pubblica nel motore (`DatabaseSignature.PUBLIC_KEY`) e nel
  launcher (`engine/compat_db.rs`); verifica con il solo JDK in Java e con
  `ed25519-dalek` nel launcher.
- **Indice** (`index.json`): sequenza, nome del file, dimensione, SHA-256,
  firma. Il launcher legge l'indice (massimo 64 KB), scarica il database solo
  se la sequenza è più nuova di quella in cache, controlla dimensione,
  SHA-256, firma e che schema e sequenza nel contenuto coincidano con
  l'indice; poi scrive prima la firma e poi il file, in modo atomico.
- **Doppia verifica**: il motore riverifica la firma della copia scaricata e
  sceglie, tra la copia nel jar e quella scaricata, la valida con sequenza
  più alta. Una copia non valida viene ignorata e riportata in
  `compatibility-report.json` (`databaseProblems`) e in F3.
- **Fine riga**: i file sono marcati `-text` in `.gitattributes`. Una
  conversione CRLF invaliderebbe la firma; un test JUnit e uno Rust lo
  controllano.

Il database non spegne mai una mod e non blocca il gioco: sceglie solo la
modalità del motore.

## Aggiornamento senza aggiornare il launcher

All'avvio il launcher chiama in background
`https://raw.githubusercontent.com/Dxxts/bloom-client/main/bloom-performance-engine/compat-db/index.json`
(sovrascrivibile con `BLOOM_COMPAT_DB_URL`) e salva la copia verificata in
`<dati del launcher>/meta/compat-db/`. Al lancio di un'istanza Bloom Native
passa il percorso con `-Dbloom.compatDb=…`. Senza rete resta in uso l'ultima
copia verificata o quella nel jar.

Per pubblicare: modificare il JSON, incrementare `sequence`, firmare, fare
commit dei tre file (`bloom-compat-db.json`, `.sig`, `index.json`).

```powershell
java scripts\CompatDbTool.java sign D:\bloom-build\keys\compat-db-ed25519.key compat-db\bloom-compat-db.json
java scripts\CompatDbTool.java verify hpPhcERyUXEn8W7tVZyQe3iB9UflpWWhDBBtT7YdwZY= compat-db\bloom-compat-db.json
```

## Chiave privata

Generata il 2026-11-01 con `CompatDbTool keygen`, salvata **solo** in
`D:\bloom-build\keys\compat-db-ed25519.key` (fuori dal repository). Va
copiata in un posto sicuro: senza, nessun database futuro può essere firmato
e la chiave pubblica andrebbe sostituita in motore e launcher.
