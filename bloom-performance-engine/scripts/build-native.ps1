<#
.SYNOPSIS
  Builds and tests the native engine, then the Fabric mod jar (which embeds
  bloom_native.dll). Build output goes to D:\bloom-build (C: has little space).
#>
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$env:PATH = "$env:USERPROFILE\.cargo\bin;$env:PATH"
$env:CARGO_TARGET_DIR = "D:\bloom-build\target"
$env:GRADLE_USER_HOME = "D:\bloom-build\gradle-home"

Push-Location (Join-Path $root "native")
try {
	cargo test --workspace
	if ($LASTEXITCODE -ne 0) { throw "cargo test failed" }
	cargo build --release -p bloom-ffi -p bloom-bench
	if ($LASTEXITCODE -ne 0) { throw "cargo build failed" }
} finally { Pop-Location }

Push-Location (Join-Path $root "java")
try {
	.\gradlew.bat build --no-daemon
	if ($LASTEXITCODE -ne 0) { throw "gradle build failed" }
} finally { Pop-Location }

Get-Item (Join-Path $root "java\build\libs\bloom-engine-*.jar") | Where-Object Name -notmatch "sources"
