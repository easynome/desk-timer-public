# ASCII only: Windows PowerShell 5.1 reads .ps1 as ANSI.
# Regression probe for a bug that cost an hour of chasing: when a second instance starts,
# it must exit quietly. Throwing instead makes jpackage's launcher pop
# "Failed to launch JVM", which reads like a broken install rather than "already running".
param(
  [int]$WaitSec = 8
)

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $root

Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -match 'desktimer\.App' } |
  ForEach-Object {
    Write-Output ("KILLING_LEFTOVER " + $_.ProcessId)
    Stop-Process -Id $_.ProcessId -Force
  }
Start-Sleep -Seconds 2

$env:TIMER_HOME = Join-Path $root "target/lock-test"
$base = @("-Xmx256m", "-Xms32m", "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8",
  "--module-path", (Join-Path $root "target/lib"), "--add-modules", "javafx.controls",
  "-cp", "target/desk-timer.jar;target/lib/*", "desktimer.App")

$a = Start-Process java -ArgumentList $base -PassThru -WindowStyle Hidden `
  -RedirectStandardOutput (Join-Path $root "target/lock-a.log")
Start-Sleep -Seconds $WaitSec

$b = Start-Process java -ArgumentList $base -PassThru -WindowStyle Hidden `
  -RedirectStandardOutput (Join-Path $root "target/lock-b.log")
Wait-Process -Id $b.Id -Timeout 30 -ErrorAction SilentlyContinue
$secondExited = $b.HasExited
$secondOut = Get-Content (Join-Path $root "target/lock-b.log") -Raw -Encoding UTF8
$firstAlive = -not $a.HasExited
Stop-Process -Id $a.Id -Force -ErrorAction SilentlyContinue
Remove-Item Env:\TIMER_HOME -ErrorAction SilentlyContinue

Write-Output ("SECOND_EXITED_CLEANLY=" + ($secondExited -and ($secondOut -match "ALREADY_RUNNING")))
Write-Output ("FIRST_UNAFFECTED=" + $firstAlive)
if (-not ($secondExited -and ($secondOut -match "ALREADY_RUNNING") -and $firstAlive)) {
  Pop-Location
  Write-Output "SINGLE_INSTANCE_CHECK_FAILED"
  exit 1
}
Pop-Location
Write-Output "SINGLE_INSTANCE_OK"
