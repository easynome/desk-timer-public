param(
  [string]$InstallRoot = "D:\timer-app",
  [string]$DataHome = "D:\timer-data",
  [Parameter(Mandatory=$true)][string]$ExpectedJarSha256,
  [string]$Hotkey = ""
)
# Explicitly authorized stop-and-replace upgrade. No recursive deletes; never restores live data.
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$image = [IO.Path]::GetFullPath((Join-Path $repo "target/dist/desk-timer"))
$root = [IO.Path]::GetFullPath($InstallRoot).TrimEnd('\','/')
$data = [IO.Path]::GetFullPath($DataHome).TrimEnd('\','/')
if ($root -eq [IO.Path]::GetPathRoot($root).TrimEnd('\') -or $data -eq [IO.Path]::GetPathRoot($data).TrimEnd('\')) { throw "ROOT_DIRECTORY_REFUSED" }
function Assert-Child([string]$parent, [string]$path) {
  $full = [IO.Path]::GetFullPath($path)
  if (-not $full.StartsWith($parent + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw "PATH_OUTSIDE_ROOT $full" }
}
function Assert-NoLinks([string]$path) {
  $cursor = Get-Item -LiteralPath $path
  while ($null -ne $cursor) {
    if ($cursor.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "LINK_REFUSED $($cursor.FullName)" }
    $cursor = $cursor.Parent
  }
  if (@(Get-ChildItem -LiteralPath $path -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count) { throw "NESTED_LINK_REFUSED" }
}
function Hashes([string]$path) {
  @(Get-ChildItem -LiteralPath $path -Recurse -Force -File | Where-Object { $_.Name -ne '.lock' } | Sort-Object FullName | ForEach-Object {
    [pscustomobject]@{ path=$_.FullName.Substring($path.Length + 1); sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLower() }
  })
}
function Assert-SameFiles($a, $b) {
  if (($a | ConvertTo-Json -Compress) -ne ($b | ConvertTo-Json -Compress)) { throw "COPY_HASH_MISMATCH" }
}
$installed = Join-Path $root "desk-timer"
$exe = Join-Path $installed "desk-timer.exe"
Assert-Child $root $installed
Assert-NoLinks $root
Assert-NoLinks $data
Assert-NoLinks $image
if (-not (Test-Path -LiteralPath $exe)) { throw "OLD_INSTALL_MISSING" }
if ($data.StartsWith($root + '\',[StringComparison]::OrdinalIgnoreCase) -or $root.StartsWith($data + '\',[StringComparison]::OrdinalIgnoreCase) -or $data -eq $root) { throw "APP_DATA_MUST_BE_SEPARATE" }
$jarHash = (Get-FileHash -LiteralPath (Join-Path $image "app/desk-timer.jar")).Hash
if ($jarHash -ne $ExpectedJarSha256) { throw "UNEXPECTED_IMAGE" }
$cfg = Get-Content -LiteralPath (Join-Path $image "app/desk-timer.cfg") -Raw
$match = [regex]::Match($cfg, 'jpackage.app-version=([0-9]+\.[0-9]+\.[0-9]+)')
if (-not $match.Success) { throw "VERSION_MISSING" }
$version = $match.Groups[1].Value
$stamp = (Get-Date -Format "yyyyMMdd-HHmmss") + "-" + [guid]::NewGuid().ToString('N').Substring(0,6)
$rollback = Join-Path $root "rollback/$stamp"
$stage = Join-Path $root "desk-timer-staged-$stamp"
$oldImage = Join-Path $rollback "desk-timer"
foreach ($candidate in @($rollback,$stage,$oldImage)) { Assert-Child $root $candidate }
New-Item -ItemType Directory -Path $rollback | Out-Null
Copy-Item -LiteralPath $image -Destination $stage -Recurse
$imageHashes = Hashes $image
Assert-SameFiles $imageHashes (Hashes $stage)
$imageHashes | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $rollback "new-image-hashes.json") -Encoding UTF8
$oldHashes = Hashes $installed
$oldHashes | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $rollback "old-image-hashes.json") -Encoding UTF8
Write-Output "STAGED_AND_VERIFIED version=$version"
# Match executable path, not process name. Both launcher and JVM share this path in jpackage.
$processes = @(Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -eq $exe })
foreach ($process in $processes) { Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Milliseconds 500
if (@(Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -eq $exe }).Count) { throw "OLD_PROCESS_STILL_RUNNING" }
# Prove the single-writer lock is released before copying SQLite + any surviving WAL together.
$lockStream = [IO.File]::Open((Join-Path $data '.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
try {
  $snapshot = Join-Path $rollback "data-before"
  New-Item -ItemType Directory -Path $snapshot | Out-Null
  Get-ChildItem -LiteralPath $data -Force | Where-Object { $_.Name -ne '.lock' } | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $snapshot -Recurse
  }
  $dataHashes = Hashes $data
  Assert-SameFiles $dataHashes (Hashes $snapshot)
  $dataHashes | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $rollback "data-before-hashes.json") -Encoding UTF8
  # Moves are within the verified install root; the old image is kept whole for rollback.
  Move-Item -LiteralPath $installed -Destination $oldImage
  Move-Item -LiteralPath $stage -Destination $installed
  Assert-SameFiles $imageHashes (Hashes $installed)
} finally { $lockStream.Dispose() }
$priorHome = $env:TIMER_HOME
try {
  $env:TIMER_HOME = $data
  $start = Get-Date
  $startArgs = @{FilePath=$exe;PassThru=$true;WindowStyle='Hidden'}
  if ($Hotkey) { $startArgs.ArgumentList = @("--hotkey=$Hotkey") }
  $p = Start-Process @startArgs
  $ready = $false
  $startupReady = $false
  for ($i=0; $i -lt 40; $i++) {
    Start-Sleep -Milliseconds 250
    $manifestPath = Join-Path $data "exports/manifest.json"
    if ((Test-Path -LiteralPath $manifestPath) -and (Get-Item -LiteralPath $manifestPath).LastWriteTime -gt $start) {
      try { $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json; $ready = $manifest.app_version -eq $version } catch { }
    }
    $runtime = Join-Path $data "logs/runtime-0.log"
    if (Test-Path $runtime) {
      $startup = Get-Content -LiteralPath $runtime -Encoding UTF8 | Where-Object { $_ -match ' STARTUP ok$' } | Select-Object -Last 1
      if ($startup) { $startupReady = [DateTimeOffset]::Parse(($startup -split ' ')[0]).UtcDateTime -ge $start.ToUniversalTime() }
    }
    if ($ready -and $startupReady) { break }
  }
  $alive = @(Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -eq $exe }).Count -gt 0
  $ready = $ready -and $startupReady
  $receipt = [pscustomobject]@{ version=$version; ready=$ready; alive=$alive; rollback=$rollback; data_home=$data; stopped_pids=@($processes | ForEach-Object { [int]$_.ProcessId }); new_jar_sha256=$jarHash.ToLower(); recorded_at=(Get-Date).ToString('o') }
  $receipt | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $rollback "upgrade-receipt.json") -Encoding UTF8
  if (-not $ready -or -not $alive) { throw "UPGRADE_VERIFY_FAILED old image and data snapshot retained at $rollback; live data NOT automatically rewound" }
  Write-Output ("UPGRADE_OK " + ($receipt | ConvertTo-Json -Compress -Depth 4))
} finally { $env:TIMER_HOME = $priorHome }
