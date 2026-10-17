param(
  [int]$Mods = 7,
  [int]$Vk = 0x54,
  [int]$Seconds = 120
)

# ASCII only: Windows PowerShell 5.1 reads .ps1 as ANSI, Chinese text breaks parsing.
#
# Negative verifier for the settings window's "hot key already taken" path. RegisterHotKey is
# thread-affine, so the combination stays held while this process sleeps.
#
#   .\check-hotkey-occupied.ps1                 -> hold ctrl+alt+shift+t (mods 7, vk 0x54)
#   .\check-hotkey-occupied.ps1 -Vk 0x46 ...    -> another combination
#
# Then run the self test in another shell:
#   .\run-app.ps1 -Task selftest
# Expected: HOTKEY_REBIND_REJECTED ... rebound=false woke_by_new=false RUN_FAILED,
# AND restored=true woke_by_old=true -- the old key must still wake the app afterwards.
# Ctrl-C here (or killing this process) releases the key again.
$sig = @"
using System;
using System.Runtime.InteropServices;
public static class HKProbe {
  [DllImport("user32.dll", SetLastError = true)]
  public static extern bool RegisterHotKey(IntPtr hWnd, int id, uint mods, uint vk);
  [DllImport("user32.dll", SetLastError = true)]
  public static extern bool UnregisterHotKey(IntPtr hWnd, int id);
}
"@
Add-Type -TypeDefinition $sig
$ok = [HKProbe]::RegisterHotKey([IntPtr]::Zero, 0x4001, [uint32]$Mods, [uint32]$Vk)
Write-Output ("HELD mods=" + $Mods + " vk=0x" + ('{0:X2}' -f $Vk) + " ok=" + $ok)
if (-not $ok) {
  Write-Output ("LAST_ERROR=" + [System.Runtime.InteropServices.Marshal]::GetLastWin32Error())
  exit 1
}
Start-Sleep -Seconds $Seconds
[void][HKProbe]::UnregisterHotKey([IntPtr]::Zero, 0x4001)
Write-Output "RELEASED"
