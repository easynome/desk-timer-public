# ASCII only: Windows PowerShell 5.1 reads .ps1 as ANSI, non-ASCII text breaks parsing.
#
# Turns the jpackage app-image (target/dist/desk-timer) into the portable zip that a release
# asset needs, then PROVES the zip is runnable instead of just extractable:
#   entries -> extract to scratch -> start it with TIMER_HOME in that scratch home ->
#   it must stay alive and write its own exports -> the consumer validator judges those bytes.
# Kills only the process it started, so a running user instance is never touched.

param(
  [string]$Version = "1.0.0",
  [string]$OutDir = "D:\timer-app\release-assets",
  [switch]$SkipRunCheck
)

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $here

$src = Join-Path $here "target\dist\desk-timer"
if (-not (Test-Path -LiteralPath $src)) {
  Write-Output ("NO_IMAGE " + $src + "  (run package.ps1 first)")
  Pop-Location
  exit 1
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$zip = Join-Path $OutDir ("desk-timer-" + $Version + "-windows-portable.zip")
if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
Compress-Archive -Path $src -DestinationPath $zip -CompressionLevel Optimal

Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [IO.Compression.ZipFile]::OpenRead($zip)
$exe = ($z.Entries | Where-Object { $_.FullName -like "*desk-timer.exe" } | Measure-Object).Count
$jar = ($z.Entries | Where-Object { $_.FullName -like "*desk-timer.jar" } | Measure-Object).Count
$fx = ($z.Entries | Where-Object { $_.FullName -like "*javafx*jar" } | Measure-Object).Count
$dll = ($z.Entries | Where-Object { $_.FullName -like "*.dll" } | Measure-Object).Count
$total = $z.Entries.Count
$z.Dispose()
$item = Get-Item -LiteralPath $zip
Write-Output ("ZIP=" + $item.FullName + " size_mb=" + [math]::Round($item.Length / 1MB, 1))
Write-Output ("ZIP_ENTRIES=" + $total + " exe=" + $exe + " jar=" + $jar +
  " javafx_jars=" + $fx + " dll=" + $dll)
if ($exe -ne 1 -or $jar -ne 1 -or $fx -lt 1) {
  Write-Output "ZIP_INCOMPLETE main jar or a JavaFX jar is missing; the extracted exe cannot start"
  Pop-Location
  exit 1
}

if ($SkipRunCheck) {
  Write-Output "RUN_CHECK_SKIPPED"
  Pop-Location
  exit 0
}

$work = Join-Path $here "target\zip-check"
$scratchHome = Join-Path $here "target\zip-check-home"
if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
if (Test-Path -LiteralPath $scratchHome) { Remove-Item -LiteralPath $scratchHome -Recurse -Force }
New-Item -ItemType Directory -Force -Path $work | Out-Null
[IO.Compression.ZipFile]::ExtractToDirectory($zip, $work)
$exePath = Join-Path $work "desk-timer\desk-timer.exe"

$env:TIMER_HOME = $scratchHome
$p = Start-Process $exePath -PassThru
Start-Sleep -Seconds 25
$alive = -not $p.HasExited
$man = Join-Path $scratchHome "exports\manifest.json"
$exported = Test-Path -LiteralPath $man
Get-CimInstance Win32_Process -Filter "ParentProcessId=$($p.Id)" -ErrorAction SilentlyContinue |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
Remove-Item Env:\TIMER_HOME
Start-Sleep -Seconds 2
$survivors = @(Get-CimInstance Win32_Process -Filter "Name='desk-timer.exe'" -ErrorAction SilentlyContinue |
  Where-Object { $_.ExecutablePath -like "*zip-check*" } | ForEach-Object { $_.ProcessId })
Write-Output ("RUN_CHECK alive_after_25s=" + $alive + " own_home_exported=" + $exported +
  " zip_check_survivors=" + $survivors.Count)
if (-not ($alive -and $exported)) {
  Write-Output "ZIP_NOT_RUNNABLE the extracted exe did not stay alive or did not export; do not publish this asset"
  Pop-Location
  exit 1
}

& node "docs/verify-contract.mjs" (Join-Path $scratchHome "exports")
if ($LASTEXITCODE -ne 0) { Pop-Location; Write-Output "CONTRACT_FAILED_ON_ZIP_EXPORT"; exit 1 }
Write-Output "PORTABLE_ZIP_OK"
Pop-Location
