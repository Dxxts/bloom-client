# Repeatable Windows build for Bloom Client.
#
# Runs every check, then produces the NSIS installer.
#   .\scripts\build-windows.ps1              # unsigned updater artifacts
#   .\scripts\build-windows.ps1 -Release     # signed updater artifacts (needs TAURI_SIGNING_PRIVATE_KEY)
#
# Optional environment: BLOOM_MS_CLIENT_ID, BLOOM_UPDATE_ENDPOINT,
# BLOOM_ANNOUNCEMENTS_URL (see docs/RELEASING.md).

param([switch]$Release)

$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    $cargoBin = Join-Path $env:USERPROFILE ".cargo\bin"
    if (Test-Path $cargoBin) { $env:Path = "$cargoBin;$env:Path" } else { throw "Rust is not installed (https://rustup.rs)" }
}

function Step($name, [scriptblock]$cmd) {
    Write-Host "==> $name" -ForegroundColor Green
    & $cmd
    if ($LASTEXITCODE -ne 0) { throw "$name failed (exit code $LASTEXITCODE)" }
}

Step "Install npm dependencies" { npm ci }
Step "TypeScript typecheck" { npm run typecheck }
Step "Frontend tests" { npm test }
Step "Rust lint (clippy -D warnings)" { cargo clippy --manifest-path src-tauri/Cargo.toml --all-targets -- -D warnings }
Step "Rust tests" { cargo test --manifest-path src-tauri/Cargo.toml }
Step "Bloom Native World Engine (Rust + Fabric mod)" {
    & "$PSScriptRoot\..\bloom-performance-engine\scripts\build-native.ps1"
    New-Item -ItemType Directory -Force src-tauri\resources\engine | Out-Null
    Copy-Item bloom-performance-engine\java\build\libs\bloom-engine-0.1.0.jar src-tauri\resources\engine\bloom-engine.jar -Force
}

if ($Release) {
    if (-not $env:TAURI_SIGNING_PRIVATE_KEY) { throw "TAURI_SIGNING_PRIVATE_KEY is required for -Release" }
    Step "Signed release build" { npm run app:release }
} else {
    Step "Release build" { npm run app:build }
}

$bundle = "src-tauri\target\release\bundle\nsis"
Get-ChildItem $bundle | ForEach-Object { Write-Host "  $($_.FullName)" }
