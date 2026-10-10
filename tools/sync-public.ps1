# ASCII only: Windows PowerShell 5.1 reads .ps1 as ANSI, non-ASCII text breaks parsing.
#
# Builds the public snapshot of this repo as a SINGLE commit and, with -Push, sends it to the
# public remote. Nothing is checked out and nothing is merged: the snapshot tree is produced
# with plumbing straight from HEAD, so the dev repo's history and working tree are untouched.
#
#   .public-exclude  pathspecs dropped from the snapshot (personal context, machine records)
#   .public-guard    fixed strings that must not appear anywhere in the snapshot
#
# Two calls on purpose: the default run is read-only and prints the verdict, -Push is the only
# path that writes to a public remote - and it re-runs the guard before doing that.

param(
  [switch]$Push
)

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent $here          # this script lives in tools/, the repo is one level up
Push-Location $repo
$publicRemote = "https://github.com/easynome/desk-timer-public.git"
$branch = "main"

function Fail([string]$why) {
  Write-Output ("REFUSED " + $why)
  Pop-Location
  exit 1
}

$head = (& git rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or -not $head) { Pop-Location; Write-Output "NO_HEAD"; exit 1 }

# Uncommitted tracked changes are NOT in the snapshot: say so instead of letting the
# reviewer assume the public tree matches the dev working tree.
$dirty = @(& git status --porcelain | Where-Object { $_.Length -gt 1 -and $_.Substring(0, 1) -ne "?" -and $_.Substring(0, 1) -ne " " })
if ($dirty.Count -gt 0) {
  Write-Output ("NOTE_UNCOMMITTED_TRACKED=" + $dirty.Count + " snapshot ships HEAD, not the working tree")
}

# Comments and blanks are dropped here: git's --pathspec-from-file treats every line literally.
$exclude = @(Get-Content ".public-exclude" -Encoding UTF8 |
  Where-Object { $_ -and -not $_.Trim().StartsWith("#") } | ForEach-Object { $_.Trim() })
if ($exclude.Count -eq 0) { Fail "empty .public-exclude" }

$idx = (Join-Path $repo "target/public-snapshot.index") -replace "\\", "/"
if (Test-Path -LiteralPath $idx) { Remove-Item -LiteralPath $idx -Force }
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $idx) | Out-Null

$env:GIT_INDEX_FILE = $idx
& git read-tree HEAD
if ($LASTEXITCODE -ne 0) { Remove-Item Env:\GIT_INDEX_FILE; Fail "read-tree" }
& git rm -r --cached --quiet --ignore-unmatch -- $exclude
if ($LASTEXITCODE -ne 0) { Remove-Item Env:\GIT_INDEX_FILE; Fail "rm-excluded" }
$tree = (& git write-tree).Trim()
Remove-Item Env:\GIT_INDEX_FILE
if ($LASTEXITCODE -ne 0 -or -not $tree) { Fail "write-tree" }

$date = (Get-Date).ToString("yyyy-MM-dd")
$msg = "snapshot: desk-timer " + $date + " from " + $head.Substring(0, 7) + " (dev-only paths excluded)"
# The snapshot's commit metadata is public too, and .public-guard can only see blob CONTENT.
# The dev repo's configured identity is a real mailbox, so the snapshot gets the GitHub noreply
# identity instead. Cost: the commit is not linked to his profile avatar. That is the point.
$noreply = "easynome@users.noreply.github.com"
$env:GIT_AUTHOR_NAME = "easynome"
$env:GIT_AUTHOR_EMAIL = $noreply
$env:GIT_COMMITTER_NAME = "easynome"
$env:GIT_COMMITTER_EMAIL = $noreply
$commit = (& git commit-tree $tree -m $msg).Trim()
Remove-Item Env:\GIT_AUTHOR_NAME, Env:\GIT_AUTHOR_EMAIL, Env:\GIT_COMMITTER_NAME, Env:\GIT_COMMITTER_EMAIL
if ($LASTEXITCODE -ne 0 -or -not $commit) { Fail "commit-tree" }

# Guard A0: the commit itself must not carry the mailbox from git config.
$ident = (& git log -1 --format="%ae %ce" $commit).Trim()
$emails = @($ident -split " ")
if ($emails.Count -ne 2 -or @($emails | Where-Object { $_ -ne $noreply }).Count -gt 0) {
  Fail ("commit identity is not the noreply pair: " + $ident)
}

# Guard A: no excluded path may survive.
$paths = @(& git ls-tree -r --name-only $commit)
$flat = @($exclude | ForEach-Object { ($_ -replace "\\", "/") -replace "/$", "" })
$leaked = @($paths | Where-Object { $p = $_; (@($flat | Where-Object { $p -like ($_ + "*") }).Count -gt 0) })
if ($leaked.Count -gt 0) { Fail ("path in snapshot: " + ($leaked -join ", ")) }

# Guard B: no forbidden string in any blob. .public-guard is itself the list of those strings,
# so it is dropped from the content scan (it would match itself by design).
& git grep -I -n -F -f ".public-guard" $commit -- . ":(exclude).public-guard" |
  ForEach-Object { Write-Output ("GUARD_HIT " + $_) }
$guardExit = $LASTEXITCODE
if ($guardExit -eq 0) { Fail "guard strings found (see GUARD_HIT lines above)" }
if ($guardExit -ne 1) { Fail ("guard scan error exit=" + $guardExit) }

Write-Output ("SNAPSHOT commit=" + $commit + " files=" + $paths.Count + " head=" + $head.Substring(0, 7))
Write-Output ("EXCLUDED " + ($exclude -join " "))

if (-not $Push) {
  Write-Output ("DRY_RUN re-run with -Push to publish to " + $publicRemote)
  Pop-Location
  exit 0
}

$haveRemote = @(& git remote | Where-Object { $_ -eq "public" }).Count
if ($haveRemote -eq 0) {
  & git remote add public $publicRemote
  if ($LASTEXITCODE -ne 0) { Fail "remote add" }
}
& git push -f public ($commit + ":refs/heads/" + $branch)
if ($LASTEXITCODE -ne 0) { Fail "push" }

# Verify against the remote, not the local object store: what a reviewer actually sees.
& git fetch --quiet public ($branch)
if ($LASTEXITCODE -ne 0) { Fail "fetch-back" }
$remoteSha = (& git rev-parse "FETCH_HEAD").Trim()
$remotePaths = @(& git ls-tree -r --name-only FETCH_HEAD)
$same = ($remoteSha -eq $commit) -and ($remotePaths.Count -eq $paths.Count)
Write-Output ("PUSHED remote_main=" + $remoteSha + " files=" + $remotePaths.Count + " matches_local=" + $same)
Write-Output ("REMOTE_HAS_NO_DEV_PATHS=" + (@($remotePaths | Where-Object {
  $p = $_; (@($flat | Where-Object { $p -like ($_ + "*") }).Count -gt 0) }).Count -eq 0))
Pop-Location
if (-not $same) { Fail "remote tree differs from the snapshot we built" }
