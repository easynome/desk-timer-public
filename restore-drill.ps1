param(
  [string]$Zip = "",
  [string]$BackupDir = "D:\timer-data\backup",
  [string]$Exe = "D:\timer-app\desk-timer\desk-timer.exe",
  [int]$RunSecs = 25
)

# ASCII only: Windows PowerShell 5.1 reads .ps1 as ANSI, Chinese text breaks parsing.
#
# M2-f: the real backup-restore drill. It answers one question and nothing else:
#   "if his machine dies tonight, does the newest zip actually give the tool back?"
#
# So it does NOT poke the running app or touch D:\timer-data: it restores the newest zip into a
# scratch TIMER_HOME, starts the INSTALLED exe against that home, lets it export for real, and
# then runs the same consumer-side contract checker the check-in tools use.
# The single-instance lock lives inside each TIMER_HOME (D:\timer-data\.lock), so the drill and
# the running app coexist - verified by check-single-instance.ps1.

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $here

if ($Zip -eq "") {
  $Zip = (Get-ChildItem (Join-Path $BackupDir "timer-backup-*.zip") |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}
if (-not (Test-Path $Zip)) {
  Write-Output ("DRILL_FAILED no backup zip under " + $BackupDir)
  Pop-Location
  exit 1
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$drillHome = Join-Path $here ("target\restore-drill\" + $stamp)
New-Item -ItemType Directory -Force -Path $drillHome | Out-Null
$unzipped = Join-Path $drillHome "unzip"
Expand-Archive -Path $Zip -DestinationPath $unzipped

$manifest = Get-Content (Join-Path $unzipped "backup-manifest.json") -Raw -Encoding UTF8 |
  ConvertFrom-Json
$dbCopy = Join-Path $unzipped "timer.db"
$actual = (Get-FileHash -Algorithm SHA256 $dbCopy).Hash.ToLower()
$shaOk = ($actual -eq $manifest.db_sha256)

New-Item -ItemType Directory -Force -Path (Join-Path $drillHome "db") | Out-Null
Copy-Item $dbCopy (Join-Path $drillHome "db\timer.db")
$restored = Join-Path $drillHome "db\timer.db"

$drv = (Get-ChildItem "target\lib\sqlite-jdbc-*.jar" | Select-Object -First 1).FullName
$verifyLine = (& java -cp $drv "tools\RestoreVerify.java" $restored 2>&1 |
  Select-Object -Last 1)
Write-Output ("RESTORE_VERIFY " + $verifyLine)
$v = $verifyLine | ConvertFrom-Json

$integrityOk = ($v.integrity -eq "ok")
$versionOk = ([int]$v.user_version -eq [int]$manifest.user_version)
$countsOk = ($v.time_entry -eq $manifest.rows.time_entry) -and
  ($v.note -eq $manifest.rows.note) -and ($v.todo -eq $manifest.rows.todo)
$noOverlap = ($v.unwaived_overlap -eq 0)

$env:TIMER_HOME = $drillHome
$p = Start-Process $Exe -PassThru
Start-Sleep -Seconds $RunSecs
$alive = -not $p.HasExited
$expDir = Join-Path $drillHome "exports"
$exported = Test-Path (Join-Path $expDir "manifest.json")

function Stop-Tree([int]$pid_) {
  Get-CimInstance Win32_Process -Filter "ParentProcessId=$pid_" -ErrorAction SilentlyContinue |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
  Stop-Process -Id $pid_ -Force -ErrorAction SilentlyContinue
}
# Only what this script started. His running instance is never touched.
Stop-Tree $p.Id
Remove-Item Env:\TIMER_HOME -ErrorAction SilentlyContinue

$contractLine = (& node "docs\verify-contract.mjs" $expDir 2>&1 | Select-Object -Last 1)
$contractOk = ($contractLine -match "CONTRACT_OK")
$stillUp = @(Get-Process desk-timer -ErrorAction SilentlyContinue).Count
$exportedRows = 0
if ($exported) {
  $exportedRows = @(Get-Content (Join-Path $expDir "time-entries.jsonl") |
    Where-Object { $_.Trim() -ne "" }).Count
}

Write-Output ("DRILL zip=" + (Split-Path $Zip -Leaf) + " sha256=" + $shaOk +
  " integrity=" + $integrityOk + " user_version=" + $versionOk + " counts=" + $countsOk +
  " db_rows=" + $v.time_entry + "/" + $manifest.rows.time_entry +
  " unwaived_overlap=" + $v.unwaived_overlap + " app_alive=" + $alive +
  " exported=" + $exported + " exported_rows=" + $exportedRows +
  " contract=" + $contractOk + " instances_left=" + $stillUp)
Write-Output ("DRILL home=" + $drillHome + " verdict_line=" + $contractLine)

if (-not ($shaOk -and $integrityOk -and $versionOk -and $countsOk -and $noOverlap -and
    $alive -and $exported -and $contractOk)) {
  Write-Output "RESTORE_DRILL_FAILED"
  Pop-Location
  exit 1
}
Write-Output "RESTORE_DRILL_OK"
Pop-Location
