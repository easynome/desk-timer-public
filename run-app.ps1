param(
  [string]$Task = "selftest",
  [string]$Xmx = "256m",
  [int]$HeartbeatSec = 1,
  [int]$RunSecs = 8,
  [string]$Prism = "",
  [string]$Hotkey = "",
  [string]$TimerHomeOverride = ""
)

# ASCII only: Windows PowerShell 5.1 reads .ps1 as ANSI, Chinese text breaks parsing.
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $here

& mvn -q -B package
if ($LASTEXITCODE -ne 0) {
  Pop-Location
  Write-Output "BUILD_OR_TEST_FAILED"
  exit 1
}

$cp = "target/desk-timer.jar;target/lib/*"

if ($TimerHomeOverride -ne "") {
  $env:TIMER_HOME = $TimerHomeOverride
} elseif ($Task -eq "selftest") {
  # selftest writes to a scratch home so it never pollutes real entries
  $scratch = Join-Path $here "target/selftest-home"
  # Start clean every run: the queue assertions count rows, and leftover
  # interrupted/overlapping rows from a previous run make them lie.
  if (Test-Path -LiteralPath $scratch) {
    Remove-Item -LiteralPath $scratch -Recurse -Force
    Write-Output "SCRATCH_CLEANED"
  }
  $env:TIMER_HOME = $scratch
}

# JavaFX must load as modules: on plain classpath Platform.startup fails with
# "JavaFX runtime components are missing".
# JDK 17 picks the console encoding from these; without them Chinese hints come out as GBK bytes.
. (Join-Path $here "tools/jvm-options.ps1")
$javaArgs = @(Get-TimerJvmOptions $Xmx)
if ($Prism -ne "") { $javaArgs += "-Dprism.order=" + $Prism }
$javaArgs += @("--module-path", (Join-Path $here "target/lib"), "--add-modules", "javafx.controls")
$javaArgs += @("-cp", $cp, "desktimer.App")
if ($Hotkey -ne "") { $javaArgs += "--hotkey=$Hotkey" }
if ($Task -eq "selftest") {
  $javaArgs += "--selftest"
  $javaArgs += "--heartbeat-sec=$HeartbeatSec"
  $javaArgs += "--run-secs=$RunSecs"
}

& java @javaArgs
$rc = $LASTEXITCODE
Pop-Location
Write-Output ("APP_EXIT=" + $rc)
exit $rc
