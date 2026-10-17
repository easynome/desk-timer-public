param(
  [string]$Xmx = "256m",
  [int]$RunSecs = 45,
  [string]$InstallTo = "",
  [switch]$SkipSmoke,
  [string]$SmokeHotkey = "ctrl+alt+f11"
)

# ASCII only: Windows PowerShell 5.1 reads .ps1 as ANSI, Chinese text breaks parsing.
# Builds two app-images from the same inputs:
#   target/dist        the shipping portable package (no console window)
#   target/dist-smoke  same thing with --win-console, so the self-test output is readable
# JavaFX jars must NOT sit next to the main jar: jpackage puts everything in --input on the
# plain classpath, and JavaFX on the classpath fails with "JavaFX runtime components are missing".
# They go into app/javafx/ after the image is built and are reached via --module-path=$APPDIR/javafx.

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $here

function Assert-BuildPath([string]$path) {
  $absolute = [IO.Path]::GetFullPath((Join-Path $here $path))
  $targetRoot = [IO.Path]::GetFullPath((Join-Path $here "target")) + [IO.Path]::DirectorySeparatorChar
  if (-not $absolute.StartsWith($targetRoot, [StringComparison]::OrdinalIgnoreCase)) { throw "UNSAFE_BUILD_PATH $absolute" }
}

& mvn -q -B package
if ($LASTEXITCODE -ne 0) {
  Pop-Location
  Write-Output "BUILD_OR_TEST_FAILED"
  exit 1
}

$in = Join-Path $here "target/dist-in"
Assert-BuildPath "target/dist-in"
if (Test-Path $in) { Remove-Item -Recurse -Force $in }
New-Item -ItemType Directory -Path $in | Out-Null
Copy-Item "target/desk-timer.jar" $in
Get-ChildItem "target/lib/*.jar" | Where-Object { $_.Name -notlike "javafx*" } | Copy-Item -Destination $in
Copy-Item "target/lib/*.dll" $in

$common = @(
  "--type", "app-image",
  "--name", "desk-timer",
  "--app-version", "1.2.3",
  "--vendor", "easynome",
  "--input", $in,
  "--main-jar", "desk-timer.jar",
  "--main-class", "desktimer.App",
  "--java-options", "--module-path=`$APPDIR/javafx",
  "--java-options", "--add-modules=javafx.controls"
)
. (Join-Path $here "tools/jvm-options.ps1")
foreach ($option in (Get-TimerJvmOptions $Xmx)) {
  $common += @("--java-options", $option)
}

function Build-Image([string]$dest, [string[]]$extra) {
  Assert-BuildPath $dest
  if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
  New-Item -ItemType Directory -Path $dest | Out-Null
  $jp = $common + @("--dest", $dest) + $extra
  & jpackage @jp
  if ($LASTEXITCODE -ne 0) { throw "JPACKAGE_FAILED $dest" }
  # $APPDIR points at the folder holding the main jar, so the JavaFX jars go beside it
  $fx = Join-Path $dest "desk-timer/app/javafx"
  New-Item -ItemType Directory -Force -Path $fx | Out-Null
  Copy-Item "target/lib/javafx*.jar" $fx
  $exe = Join-Path $dest "desk-timer/desk-timer.exe"
  if (-not (Test-Path $exe)) { throw "NO_EXE $exe" }
  $sum = (Get-ChildItem (Join-Path $dest "desk-timer") -Recurse | Measure-Object Length -Sum).Sum
  Write-Output ("IMAGE " + $exe + " size_mb=" + [math]::Round($sum / 1MB, 1))
}

Build-Image "target/dist" @()
Write-Output "PACKAGE_OK"

if (-not $SkipSmoke) {
  Build-Image "target/dist-smoke" @("--win-console")
  $smokeExe = Join-Path $here "target/dist-smoke/desk-timer/desk-timer.exe"
  $smokeHome = Join-Path $here "target/dist-smoke-home"
  Assert-BuildPath "target/dist-smoke-home"
  if (Test-Path $smokeHome) { Remove-Item -Recurse -Force $smokeHome }
  $env:TIMER_HOME = $smokeHome
  $out = Join-Path $here "target/smoke.log"
  $p = Start-Process $smokeExe -ArgumentList @("--selftest", "--run-secs=$RunSecs", "--hotkey=$SmokeHotkey") `
    -RedirectStandardOutput $out -PassThru -WindowStyle Hidden
  # The stages now carry deliberate waits (each "end a real segment" step must first clear
  # the misfire threshold), so the wall clock they need grew. RunSecs is only the tail wait.
  Wait-Process -Id $p.Id -Timeout ($RunSecs + 540) -ErrorAction SilentlyContinue
  if (-not $p.HasExited) {
    Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
    Write-Output "SMOKE_TIMEOUT"
  }
  Remove-Item Env:\TIMER_HOME
  # The .exe is a launcher stub: its exit code says nothing about the JVM inside it.
  # The verdict comes from the log the app wrote, since every self-test stage throws on failure.
  $log = Get-Content $out -Raw -Encoding UTF8
  foreach ($tag in @("SELFTEST_OK", "SELFTEST_UI_OK", "SELFTEST_QUEUE_OK", "SELFTEST_NOTE_OK",
      "SELFTEST_BACKUP", "SELFTEST_TODO_OK", "SELFTEST_INBOX_OK", "SELFTEST_DND_OK", "SELFTEST_POMODORO_OK", "SELFTEST_AUTOSTART_OK", "SELFTEST_PARALLEL_OK", "SELFTEST_OVERLAP_OK", "SELFTEST_THEME_OK", "SELFTEST_NOTES_OK", "SELFTEST_PLACE_OK", "SELFTEST_KEYMAP_OK", "SELFTEST_SETTINGS_OK",
      "RUN_FAILED")) {
    Write-Output ("SMOKE " + $tag + "=" + ($log -match $tag))
  }
  # Fail the command, not just the log: todo.md points at this as the acceptance entry.
  $missing = @("SELFTEST_OK", "SELFTEST_UI_OK", "SELFTEST_QUEUE_OK", "SELFTEST_NOTE_OK",
      "SELFTEST_BACKUP", "SELFTEST_TODO_OK", "SELFTEST_INBOX_OK", "SELFTEST_DND_OK", "SELFTEST_POMODORO_OK", "SELFTEST_AUTOSTART_OK", "SELFTEST_PARALLEL_OK", "SELFTEST_OVERLAP_OK", "SELFTEST_THEME_OK", "SELFTEST_NOTES_OK", "SELFTEST_PLACE_OK", "SELFTEST_KEYMAP_OK",
      "SELFTEST_SETTINGS_OK") | Where-Object { $log -notmatch $_ }
  if ($missing -or ($log -match "RUN_FAILED")) {
    Pop-Location
    Write-Output ("SMOKE_FAILED missing=" + ($missing -join ","))
    exit 1
  }
  # PS 5.1 reads files as ANSI by default, which mangles the UTF-8 log the JVM just wrote
  $log -split "`r?`n" |
    Select-String -Pattern "SELFTEST_|WAKE|RUN_FAILED|Exception" |
    ForEach-Object { $_.Line }
}

if ($InstallTo -ne "") {
  $exe = Join-Path $InstallTo "desk-timer\desk-timer.exe"

  # Copying over a running instance half-fails: the exe and several dlls are locked,
  # but app/desk-timer.jar is NOT - so you silently end up with a new jar on an old launcher.
  # Refuse to copy at all when something is running; verify byte-for-byte when we do copy.
  $pre = @(Get-Process desk-timer -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })
  if ($pre.Count -eq 0) {
    New-Item -ItemType Directory -Force -Path $InstallTo | Out-Null
    Copy-Item -Recurse -Force (Join-Path $here "target\dist\desk-timer") $InstallTo
    $srcJar = (Get-FileHash (Join-Path $here "target\dist\desk-timer\app\desk-timer.jar")).Hash
    $dstJar = (Get-FileHash (Join-Path $InstallTo "desk-timer\app\desk-timer.jar")).Hash
    $srcExe = (Get-FileHash (Join-Path $here "target\dist\desk-timer\desk-timer.exe")).Hash
    $dstExe = (Get-FileHash $exe).Hash
    $same = ($srcJar -eq $dstJar) -and ($srcExe -eq $dstExe)
    Write-Output ("INSTALL_COPIED jar_matches=" + ($srcJar -eq $dstJar) + " exe_matches=" + ($srcExe -eq $dstExe))
    if (-not $same) { Pop-Location; Write-Output "INSTALL_MISMATCH"; exit 1 }
  } else {
    Write-Output ("INSTALL_SKIPPED_ALREADY_RUNNING " + ($pre -join ",") + " is running; refusing to half-overwrite the install dir (new jar on old launcher). Quit from the tray and re-run.")
  }

  # A second instance now exits quietly (that is the point of the single-instance fix), so an
  # already-running app would make "did it start?" unreadable. Say which case we are in.
  # And never kill what we did not start: the pre-existing instance is the user's app.
  function Stop-Tree([int]$pid_) {
    Get-CimInstance Win32_Process -Filter "ParentProcessId=$pid_" -ErrorAction SilentlyContinue |
      ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Stop-Process -Id $pid_ -Force -ErrorAction SilentlyContinue
  }

  if ($pre.Count -eq 0) {
    # No competition: probe the real default home, with no TIMER_HOME set at all.
    $man = "D:\timer-data\exports\manifest.json"
    $before = "absent"
    if (Test-Path $man) { $before = (Get-Item $man).LastWriteTime.ToString("HH:mm:ss") }
    Remove-Item Env:\TIMER_HOME -ErrorAction SilentlyContinue
    $p = Start-Process $exe -PassThru
    Start-Sleep -Seconds 25
    $alive = -not $p.HasExited
    $after = "absent"
    if (Test-Path $man) { $after = (Get-Item $man).LastWriteTime.ToString("HH:mm:ss") }
    Stop-Tree $p.Id
    Write-Output ("INSTALL " + $exe + " alive_after_25s=" + $alive + " manifest " + $before + " -> " + $after)
    if (-not $alive) { Pop-Location; exit 1 }
  } else {
    # The install dir is stale, so probe the freshly built image instead: a stale install
    # would prove nothing about this build. Scratch home; user's instance untouched.
    $probeExe = Join-Path $here "target\dist\desk-timer\desk-timer.exe"
    $probeHome = Join-Path $here "target/dist-install-home"
    if (Test-Path $probeHome) { Remove-Item -Recurse -Force $probeHome }
    $env:TIMER_HOME = $probeHome
    $p = Start-Process $probeExe -PassThru
    Start-Sleep -Seconds 14
    $alive = -not $p.HasExited
    $exported = Test-Path (Join-Path $probeHome "exports\manifest.json")
    Stop-Tree $p.Id
    Remove-Item Env:\TIMER_HOME -ErrorAction SilentlyContinue
    $survivors = @(Get-Process desk-timer -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })
    Write-Output ("INSTALL_PROBE_BUILT_IMAGE alive=" + $alive + " own_home_exported=" + $exported + " user_instance_still_up=" + ($survivors.Count -ge $pre.Count))
    if (-not ($alive -and $exported)) { Pop-Location; exit 1 }
  }
}

Pop-Location
