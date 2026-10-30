<#
.SYNOPSIS
  Runs the in-game A/B benchmark: vanilla renderer, then Bloom native engine,
  same world, same options, uncapped frame rate. The game window is moved to
  the monitor given by -Display (default: the primary monitor).

.EXAMPLE
  .\scripts\run-benchmark.ps1 -World "New World" -Duration 30
#>
param(
	[string]$World = "New World",
	[string]$BenchWorld = "bloom-bench",
	[int]$Duration = 30,
	[string]$Display = ""
)
$ErrorActionPreference = "Stop"
$java = Join-Path $PSScriptRoot "..\java"
$options = Join-Path $java "run\options.txt"
$env:GRADLE_USER_HOME = "D:\bloom-build\gradle-home"

Add-Type -AssemblyName System.Windows.Forms
$screen = if ($Display) { [System.Windows.Forms.Screen]::AllScreens | Where-Object DeviceName -eq $Display } else { [System.Windows.Forms.Screen]::PrimaryScreen }
if (-not $screen) { $screen = [System.Windows.Forms.Screen]::PrimaryScreen }

Add-Type -TypeDefinition @'
using System; using System.Runtime.InteropServices; using System.Text;
public static class BloomWin {
  public delegate bool EnumProc(IntPtr h, IntPtr p);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr p);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr a, int x, int y, int cx, int cy, uint f);
  public static IntPtr Find(int pid) {
    IntPtr found = IntPtr.Zero;
    EnumWindows((h, p) => {
      if (!IsWindowVisible(h)) return true;
      var sb = new StringBuilder(256); GetWindowText(h, sb, 256);
      uint owner; GetWindowThreadProcessId(h, out owner);
      if (sb.ToString().StartsWith("Minecraft") && System.Diagnostics.Process.GetProcessById((int)owner).ProcessName.StartsWith("java")) { found = h; return false; }
      return true;
    }, IntPtr.Zero);
    return found;
  }
}
'@

# Identical, uncapped settings for both runs; the user's options come back afterwards.
$backup = "$options.bloom-backup"
Copy-Item $options $backup -Force
try {
	# Minecraft cannot read options.txt with a BOM: write plain UTF-8.
	$lines = (Get-Content $options) -replace '^enableVsync:.*', 'enableVsync:false' -replace '^maxFps:.*', 'maxFps:260'
	[System.IO.File]::WriteAllLines($options, [string[]]$lines, (New-Object System.Text.UTF8Encoding $false))
	# Both runs load a fresh copy of the same world (a name without spaces survives the
	# Gradle → JVM argument passing; the original world is never touched).
	$saves = Join-Path $java "run\saves"
	$source = Join-Path $saves $World
	if (-not (Test-Path $source)) { throw "world '$World' not found in $saves" }
	foreach ($engine in @("false", "true")) {
		$label = if ($engine -eq "true") { "Bloom native" } else { "vanilla" }
		Write-Host "== $label"
		$copy = Join-Path $saves $BenchWorld
		if (Test-Path $copy) { Remove-Item $copy -Recurse -Force }
		Copy-Item $source $copy -Recurse
		$proc = Start-Process -FilePath (Join-Path $java "gradlew.bat") -WorkingDirectory $java -NoNewWindow -PassThru `
			-ArgumentList "runBenchmark", "--no-daemon", "-q", "-Pbench.world=$BenchWorld", "-Pbench.engine=$engine", "-Pbench.duration=$Duration"
		$deadline = (Get-Date).AddMinutes(6); $moved = $false
		while (-not $proc.HasExited -and (Get-Date) -lt $deadline) {
			if (-not $moved) {
				$h = [BloomWin]::Find(0)
				if ($h -ne [IntPtr]::Zero) {
					$b = $screen.Bounds
					[void][BloomWin]::ShowWindow($h, 9)
					[void][BloomWin]::SetWindowPos($h, [IntPtr]::Zero, $b.X + 80, $b.Y + 80, 1600, 900, 0x0040)
					Start-Sleep -Milliseconds 300
					[void][BloomWin]::ShowWindow($h, 3)
					$moved = $true
				}
			}
			Start-Sleep -Milliseconds 500
		}
		if (-not $proc.HasExited) { & taskkill /T /F /PID $proc.Id | Out-Null; throw "benchmark ($label) timed out" }
	}
} finally {
	Move-Item $backup $options -Force
}
Get-ChildItem (Join-Path $java "run\.bloom\benchmarks") | Sort-Object LastWriteTime | Select-Object -Last 2 | ForEach-Object { $_.FullName }
