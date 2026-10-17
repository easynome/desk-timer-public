param(
  [string]$Xmx = "256m",
  [int]$IdleSec = 90,
  [int]$ShownSec = 30,
  [string]$HomeDir = "target/measure-home",
  [string]$Exe = ""
)

# ASCII only: Windows PowerShell 5.1 reads .ps1 as ANSI.
# Measures the two states the memory gate actually cares about:
#   idle  = resident in tray, no window
#   shown = capture window opened by the global hotkey
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $here
$probeHome = [IO.Path]::GetFullPath((Join-Path $here $HomeDir))
$targetRoot = [IO.Path]::GetFullPath((Join-Path $here "target")) + [IO.Path]::DirectorySeparatorChar
if (-not $probeHome.StartsWith($targetRoot, [StringComparison]::OrdinalIgnoreCase)) {
  throw "Measurement home must be inside this repository's target directory."
}
if (Test-Path -LiteralPath $probeHome) { Remove-Item -LiteralPath $probeHome -Recurse -Force }

. (Join-Path $here "tools/jvm-options.ps1")
$a = @(Get-TimerJvmOptions $Xmx) + @("--module-path", (Join-Path $here "target/lib"),
  "--add-modules", "javafx.controls", "-cp", "target/desk-timer.jar;target/lib/*",
  "desktimer.App", "--hotkey=ctrl+alt+f11")
$env:TIMER_HOME = $probeHome
$log = Join-Path $here "target/measure.log"
$errLog = Join-Path $here "target/measure-error.log"
if ($Exe -ne "") {
  # packaged image: -Xmx and the module path already live in the .cfg, so pass nothing
  $p = Start-Process $Exe -ArgumentList @("--hotkey=ctrl+alt+f11") -PassThru -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError $errLog
  Write-Output ("LAUNCHED " + $Exe)
} else {
  $p = Start-Process java -ArgumentList $a -PassThru -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError $errLog
  Write-Output ("LAUNCHED java -Xmx" + $Xmx)
}
Write-Output ("PID " + $p.Id)

function Sample([string]$label) {
  $proc = Resolve-Jvm
  $proc.Refresh()
  $mb = [math]::Round($proc.WorkingSet64 / 1MB, 1)
  $priv = [math]::Round($proc.PrivateMemorySize64 / 1MB, 1)
  Write-Output ("RSS_MB " + $mb + " PRIVATE_MB " + $priv + " SAMPLED_PID " + $proc.Id + " STATE " + $label)
}

# jpackage's .exe is a stub that starts the JVM as a child; the stub's own working set is a
# few MB, which would make the whole gate meaningless. The java -cp path has no child.
function Resolve-Jvm {
  $kids = Get-CimInstance Win32_Process -Filter "ParentProcessId=$($p.Id)" -ErrorAction SilentlyContinue
  if ($kids) {
    foreach ($k in $kids) { Write-Host ("CHILD " + $k.ProcessId + " " + $k.Name) }
    return Get-Process -Id (($kids | Select-Object -First 1).ProcessId)
  }
  return $p
}

Start-Sleep -Seconds 3
Resolve-Jvm | Out-Null

Start-Sleep -Seconds $IdleSec
Sample "idle-no-window"

# open the capture window through the real global hotkey path
Add-Type -AssemblyName System.Windows.Forms
if ((Get-Content -LiteralPath $log -Raw) -notmatch 'HOTKEY ok=true spec=ctrl\+alt\+f11') {
  $jvm = Resolve-Jvm
  Stop-Process -Id $jvm.Id -Force -ErrorAction SilentlyContinue
  Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
  throw "Measurement hotkey unavailable; refusing to send keys to another application."
}
[System.Windows.Forms.SendKeys]::SendWait("^(%{F11})")
Start-Sleep -Seconds 3
Sample "hotkey-sent"
Start-Sleep -Seconds $ShownSec
Sample "window-shown"

# killing the stub does not kill the JVM child on Windows, so take both
$jvm = Resolve-Jvm
Stop-Process -Id $jvm.Id -Force -ErrorAction SilentlyContinue
Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1
Pop-Location
Remove-Item Env:\TIMER_HOME -ErrorAction SilentlyContinue
Write-Output "MEASURE_DONE"
