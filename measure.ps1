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
if (Test-Path $HomeDir) { Remove-Item -Recurse -Force $HomeDir }

$a = @("-Xmx$Xmx", "-Xms32m", "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8",
  "-Dsun.stderr.encoding=UTF-8", "--module-path", (Join-Path $here "target/lib"),
  "--add-modules", "javafx.controls", "-cp", "target/desk-timer.jar;target/lib/*",
  "desktimer.App")
$env:TIMER_HOME = $HomeDir
if ($Exe -ne "") {
  # packaged image: -Xmx and the module path already live in the .cfg, so pass nothing
  $p = Start-Process $Exe -PassThru -NoNewWindow
  Write-Output ("LAUNCHED " + $Exe)
} else {
  $p = Start-Process java -ArgumentList $a -PassThru -NoNewWindow
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
    foreach ($k in $kids) { Write-Output ("CHILD " + $k.ProcessId + " " + $k.Name) }
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
[System.Windows.Forms.SendKeys]::SendWait("^(%{F12})")
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
Write-Output "MEASURE_DONE"
