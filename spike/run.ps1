param(
  [string]$Xmx = "256m",
  [string]$Prism = "",
  [int]$Cycles = 20,
  [int]$StaySec = 0,
  [int]$IdleSec = 120,
  [int]$SampleSec = 5,
  [string]$DestroyScene = "true",
  [string]$Show = "true",
  [string]$JvmExtra = "",
  [string]$Label = "run"
)

# ASCII only: Windows PowerShell 5.1 reads .ps1 as ANSI, Chinese text breaks parsing.
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$lib = Join-Path $here "target/lib"
$jar = Join-Path $here "target/spike.jar"
$out = Join-Path $here ("target/java-" + $Label + ".log")
$rssLog = Join-Path $here ("target/rss-" + $Label + ".log")
if (Test-Path $out) { Remove-Item $out -Force }
if (Test-Path $rssLog) { Remove-Item $rssLog -Force }

$javaArgs = @("-Xmx" + $Xmx, "-Xms32m")
if ($Prism -ne "") { $javaArgs += "-Dprism.order=" + $Prism }
if ($JvmExtra -ne "") { $javaArgs += $JvmExtra.Split(" ") }
$javaArgs += @("--module-path", $lib, "--add-modules", "javafx.graphics")
$javaArgs += @("-cp", $jar, "spike.Launcher")
$javaArgs += "--cycles=" + $Cycles
$javaArgs += "--stay-sec=" + $StaySec
$javaArgs += "--capture=" + (Join-Path $here "target/stay-capture.png")
$javaArgs += "--idle-sec=" + $IdleSec
$javaArgs += "--show=" + $Show.ToString().ToLower()
$javaArgs += "--destroy-scene=" + $DestroyScene.ToString().ToLower()

Write-Output ("LABEL " + $Label + " java " + ($javaArgs -join " "))
$proc = Start-Process -FilePath "java" -ArgumentList $javaArgs -NoNewWindow -PassThru -RedirectStandardOutput $out

while (-not $proc.HasExited) {
  Start-Sleep -Seconds $SampleSec
  try {
    $proc.Refresh()
    $mb = [math]::Round($proc.WorkingSet64 / 1MB, 1)
    $ts = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
    Add-Content -Path $rssLog -Value ("RSS_MB=" + $mb + " ts=" + $ts)
  } catch {
    Write-Output "SAMPLE_FAILED process exited"
  }
}

Write-Output "==== phases (java side) ===="
Get-Content $out
Write-Output "==== rss summary (sampled by PID) ===="
$lines = Get-Content $rssLog
$values = $lines | ForEach-Object { [double](($_ -split " ")[0] -replace "RSS_MB=", "") }
$stat = $values | Measure-Object -Average -Maximum -Minimum
Write-Output ("SAMPLES " + $values.Count)
Write-Output ("FIRST " + $values[0])
Write-Output ("LAST " + $values[-1])
Write-Output ("MAX " + [math]::Round($stat.Maximum, 1))
Write-Output ("MIN " + [math]::Round($stat.Minimum, 1))
Write-Output ("AVG " + [math]::Round($stat.Average, 1))
