# Bloom Client: architecture

Bloom is a Tauri 2 desktop application. It has two parts:

- A **React/TypeScript frontend**. It only renders state and sends intents.
- A **Rust backend**. It does all networking, file system work, process
  management and credential handling.

The two talk only through typed Tauri commands (request/response) and events
(push from the backend). The frontend has no file system, shell, HTTP or
dialog permissions: see `src-tauri/capabilities/main.json`.

```
┌──────────────────────────── WebView2 ───────────────────────────┐
│ pages/ ─ features/ ─ components/        stores/ (Zustand)       │
│              │                               ▲                  │
│        services/api.ts (invoke)     services/events.ts (listen) │
└──────────────┼───────────────────────────────┼──────────────────┘
               ▼ commands                      │ events (bloom://*)
┌──────────────────────────── Rust ───────────────────────────────┐
│ commands/  (validation, thin)                                   │
│   ├─ authentication/  Microsoft → Xbox → XSTS → Minecraft       │
│   ├─ minecraft/       manifests, version model, rules, args     │
│   ├─ installation/    plan → verify → download → finalize       │
│   ├─ downloads/       parallel, resumable, SHA1-verified        │
│   ├─ java/            detection, probing, managed runtimes      │
│   ├─ instances/       CRUD, loader adapters, mods & packs       │
│   ├─ launcher/        pipeline, hooks, process, sessions        │
│   ├─ updater/         signed self-update                        │
│   ├─ database/        SQLite + migrations                       │
│   └─ services/        AppState, settings, logging, tray         │
└─────────────────────────────────────────────────────────────────┘
```

## Backend modules

| Module | Responsibility |
|---|---|
| `errors` | `BloomError`: one enum, serialized as `{ code, message }`. The frontend switches on `code` (e.g. `auth_expired`, `already_running`). |
| `models` | camelCase DTOs shared with `src/types/index.ts`. |
| `database` | A single SQLite connection behind a mutex (WAL mode). `PRAGMA user_version` migrations. Before any upgrade, a backup is written with `VACUUM INTO bloom.db.bak-v<N>`. Typed repositories live in `repos.rs`. |
| `system` | Path layout (`GamePaths`), path validation (`safe_join`, `validate_file_name`, `validate_id`), host info (sysinfo; GPU from the Windows registry). |
| `downloads` | `Downloader` (reqwest + rustls, HTTPS only). Bounded concurrency, retries with exponential backoff and jitter, resume through HTTP `Range` on `.part` files, SHA1 and size verification while streaming, atomic rename. `ProgressTracker` holds atomic counters, and one reporter task emits at most 8 events per second. |
| `minecraft` | `manifest.rs`: official version manifest (memory → disk → ETag revalidation → stale disk when offline). Version JSON resolution checks the published SHA1 and merges `inheritsFrom`. `version.rs`: typed version JSON. `rules.rs`: the official rule semantics (os name/arch/version regex/`versionRange`, features). `arguments.rs`: modern and legacy argument building. `news.rs`: Mojang patch notes. |
| `installation` | `plan_version` turns metadata into the exact file list for this OS/arch, plus the classpath and natives. `ensure_files` verifies files (size, then SHA1, using a persistent `file_cache` keyed by path+size+mtime) and downloads only what is missing or corrupt. `install_version` holds a global install lock, which makes it idempotent and safe under concurrency. Also: legacy virtual assets and pre-1.6 `resources`, natives extraction (zip-slip safe), version deletion, pruning of unused files. |
| `java` | Detection: JAVA_HOME, PATH, registry, vendor folders, official launcher runtimes, Bloom runtimes. Each candidate is run with `-XshowSettings:properties`. Managed runtimes come from Mojang's Java runtime manifest, matched by the version's `javaVersion.component`, with every file SHA1-verified. |
| `authentication` | OAuth Authorization Code + PKCE with a loopback listener. Xbox/XSTS/Minecraft token exchange, entitlement check, profile, skin cache. `SecretStore` trait with a chunked Windows Credential Manager backend. |
| `instances` | Instance CRUD, duplication (copies worlds and config, never `.bloom`), `LoaderAdapter` trait (Vanilla implemented; Fabric/Forge/NeoForge explicitly unavailable), mods and resource packs. |
| `launcher` | `start` → `run`: account session → loader profile → install/verify → Java → `LaunchCommand` → hooks → spawn → monitor. `SessionRegistry` blocks duplicate launches and buffers the last 5000 output lines. `process.rs` reads stdout/stderr, reassembles log4j XML events into text lines, redacts the access token, batches log events (100 ms) and writes `<instance>/.bloom/logs/launch-*.log`. |
| `updater` | `tauri-plugin-updater` with a minisign public key; the endpoint is set at build time. |
| `services` | `AppState`, settings validation and side effects (log level reload, autostart), event names, tray. |

## Data layout

```
%APPDATA%\com.bloomclient.launcher\
├─ bloom.db                       settings, accounts (public data), instances,
│                                 installed versions, Java runtimes, launch
│                                 history, verified-file cache, HTTP cache
├─ bloom.db.bak-v<N>              automatic pre-migration backups
└─ game\                          (configurable: Settings → Launcher)
   ├─ meta\                       cached manifests and news
   ├─ versions\<id>\<id>.json|jar
   ├─ libraries\                  shared, content-verified
   ├─ assets\indexes|objects|virtual|log_configs
   ├─ runtimes\<component>\       managed Java runtimes
   ├─ cache\skins\
   └─ instances\<uuid>\           (configurable: Settings → Minecraft)
      ├─ saves, mods, resourcepacks, options.txt, …   ← isolated per instance
      └─ .bloom\natives, .bloom\logs                   ← launcher internals
%LOCALAPPDATA%\com.bloomclient.launcher\logs\bloom.*.log   launcher logs (7 days)
Windows Credential Manager: com.bloomclient.launcher / account:<uuid>[#n]
```

Shared data is immutable and content-addressed. Everything mutable lives
inside the instance directory, so instances never share writable state.

## Frontend

- `app/`: bootstrap (`App.tsx`: boot, event wiring, theme/motion, startup
  update check) and the router (hash router; the layout persists across
  pages, so navigation never reloads).
- `layouts/AppLayout.tsx`: title bar, sidebar, animated outlet, global hosts
  (instance editor, toasts, dialogs).
- `stores/`: Zustand stores. `sessionsStore` keeps launch state and progress;
  `logsStore` is separate, so log bursts only re-render the console.
- `services/api.ts`: the only module that calls `invoke`. Errors are
  normalized to `ApiError { code, message }`.
- `features/*`: domain components. `pages/*`: route screens.
- `i18n/`: English (source of keys) and Italian. A type check guarantees
  completeness.
- Remote HTML (patch notes) goes through `utils/sanitize.ts`: DOMParser
  (inert) plus an allow-list converted to React nodes. Nothing uses
  `innerHTML`.

Motion respects the `reducedMotion` setting through `MotionConfig`, and CSS
animations are disabled through `data-reduced-motion`.

## Event flow of a launch

```
UI ──launch_instance──▶ launcher::start ─┐ (returns immediately)
                                         ▼
  bloom://game-state   ◀── SessionRegistry (preparing → … → running → exited/crashed/failed)
  bloom://progress     ◀── ProgressTracker (checking_files / downloading / installing, real counters)
  bloom://game-log     ◀── process batcher (≤10 batches/s)
  bloom://instances-changed ◀── after install and on exit (play time)
```

Nothing polls. The UI rehydrates on mount with `get_sessions` and
`get_session_logs`.

## Security model

- Least-privilege capabilities: the frontend may only listen to events and
  control its own window.
- Every command input is validated in Rust: ids (`validate_id`), file names
  (`validate_file_name`), user directories (absolute, no `..`), JVM
  arguments (no `-cp`, `-jar`, memory flags, non-flag tokens), GUID client IDs.
- Untrusted paths (manifests, archive entries, runtime manifests) are joined
  with `safe_join`, or `enclosed_name` for zip entries.
- All downloads use HTTPS and are verified against the SHA1 published by
  Mojang when one exists. A downloaded file reaches its final path only after
  verification.
- Secrets live only in the OS credential store. Access tokens are redacted
  from game output, launch logs and launcher logs.
- Strict CSP (`script-src 'self'`; images from `self`, `data:` and
  `launchercontent.mojang.com` only), `freezePrototype`.
- Update packages are verified against an embedded minisign public key.

## Extension points for future milestones

These boundaries exist now, so later features do not need a rewrite. None
of the future systems is stubbed out or simulated.

| Future feature | Where it plugs in |
|---|---|
| Fabric / Forge / NeoForge | Implement `LoaderAdapter::prepare` to produce a version profile (Fabric profiles use `inheritsFrom`; `VersionJson::merge_parent` and maven-style library resolution in `plan_version` already handle that). Set `is_available() = true`. |
| Bloom Performance Engine, FPS boost, RAM/CPU profiles | `launcher::hooks::LaunchHook` gets the full, mutable `LaunchCommand` (JVM args, agents, classpath, env) before spawn. Profiles map onto it. |
| Client-side modifications, proprietary mods, HUDs, cosmetics | Ship as Bloom-managed artifacts installed by a new installation step (reuse `ensure_files` for verified downloads) and inject them through a `LaunchHook`. |
| In-game component updates | Same verified download pipeline (`Downloader` + `ensure_files`) with a Bloom component manifest. |
| Friends / social | A new backend service module with its own commands and events, using the account's Minecraft token from `authentication::launch_session`. |
| Linux / macOS | `keyring` backends selected in `Cargo.toml`; `system::mojang_os_name/arch` and the rule engine are already platform-aware. The managed Java `platform_key` covers macOS/Linux. |
