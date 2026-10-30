# Building and releasing Bloom

## Prerequisites (Windows)

| Tool | Version used | Install |
|---|---|---|
| Rust (MSVC toolchain) | 1.82+ (tested with 1.99) | <https://rustup.rs> → `rustup-init.exe` |
| Visual Studio Build Tools | 2022 or newer, workload **Desktop development with C++** | <https://visualstudio.microsoft.com/visual-cpp-build-tools/> |
| Microsoft Edge WebView2 Runtime | Preinstalled on Windows 10 (21H2+) and 11 | <https://developer.microsoft.com/microsoft-edge/webview2/> |
| Node.js | 20+ (tested with 24) | <https://nodejs.org> |

## Development

```powershell
npm install
npm run app:dev          # Vite dev server + Tauri window with hot reload
```

## Checks

```powershell
npm run typecheck        # TypeScript
npm test                 # frontend unit tests (Vitest)
npm run check:rust       # cargo check + clippy -D warnings
npm run test:rust        # Rust unit tests
```

## Windows installer (unsigned updater artifacts)

```powershell
$env:BLOOM_MS_CLIENT_ID = "<your Azure client ID>"      # optional, see MICROSOFT_AUTH.md
npm run app:build
```

Output: `src-tauri/target/release/bundle/nsis/Bloom Client_<version>_x64-setup.exe`.
The installer installs per user (no administrator rights needed). If the
WebView2 runtime is missing, it downloads it.

Or run `scripts/build-windows.ps1`. It runs every check and then builds.

## Signed releases with auto-update

Bloom uses the official Tauri updater (`tauri-plugin-updater`). Each update
package is signed with a **minisign** key. The launcher refuses any package
whose signature does not match the public key embedded in
`src-tauri/tauri.conf.json` → `plugins.updater.pubkey`.

### Signing key

A key pair was generated during development:

- private key: `%USERPROFILE%\.tauri\bloom-updater.key` (**never commit it**; `.gitignore` excludes `*.key`)
- public key: `%USERPROFILE%\.tauri\bloom-updater.key.pub`, already copied into `tauri.conf.json`

For production, generate your own password-protected key and replace the
public key in `tauri.conf.json`:

```powershell
npx tauri signer generate -w "$env:USERPROFILE\.tauri\bloom-updater.key"
```

Back up the private key and its password. If you lose them, existing
installations cannot verify future updates.

### Build a release

```powershell
$env:TAURI_SIGNING_PRIVATE_KEY = Get-Content "$env:USERPROFILE\.tauri\bloom-updater.key" -Raw
$env:TAURI_SIGNING_PRIVATE_KEY_PASSWORD = "<password>"     # empty for the dev key
$env:BLOOM_UPDATE_ENDPOINT = "https://<your-host>/bloom/latest.json"
$env:BLOOM_MS_CLIENT_ID = "<your Azure client ID>"
npm run app:release
```

`app:release` merges `src-tauri/tauri.release.conf.json`, which enables
`createUpdaterArtifacts`. Next to the installer it produces
`...-setup.exe.sig`.

### Update feed

Host a static `latest.json` at `BLOOM_UPDATE_ENDPOINT`. It must use HTTPS;
Bloom refuses plain HTTP.

```json
{
  "version": "0.2.0",
  "notes": "What's new in this release",
  "pub_date": "2026-11-15T12:00:00Z",
  "platforms": {
    "windows-x86_64": {
      "signature": "<contents of Bloom Client_0.2.0_x64-setup.exe.sig>",
      "url": "https://<your-host>/bloom/Bloom%20Client_0.2.0_x64-setup.exe"
    }
  }
}
```

A GitHub Releases asset URL works as an endpoint too
(`https://github.com/<org>/<repo>/releases/latest/download/latest.json`).

Builds without `BLOOM_UPDATE_ENDPOINT` show "update channel not configured"
in Settings → About, and do not check for updates.

### Code signing (Authenticode)

The installer is not Authenticode-signed until a code-signing certificate is
available. To sign it, add to `tauri.conf.json` → `bundle.windows`:

```json
"certificateThumbprint": "<thumbprint>",
"digestAlgorithm": "sha256",
"timestampUrl": "http://timestamp.digicert.com"
```

Without Authenticode, Windows SmartScreen shows a warning on first install.
Updater signatures (minisign) are separate, and they are enforced either way.

## Optional build-time configuration

| Variable | Purpose |
|---|---|
| `BLOOM_MS_CLIENT_ID` | Azure application client ID for Microsoft sign-in |
| `BLOOM_UPDATE_ENDPOINT` | HTTPS URL of the updater `latest.json` |
| `BLOOM_ANNOUNCEMENTS_URL` | HTTPS URL of a JSON array of Bloom announcements (`NewsItem` shape: `id`, `title`, `version`, `category`, `date`, `imageUrl`, `bodyHtml`, `source`), shown before Mojang patch notes on the Home page |
