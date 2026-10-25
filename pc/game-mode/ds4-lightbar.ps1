# Runs at logon (scheduled task "LunaTV DS4 Lightbar"). The DS4 clone boots into its bright default light bar and ignores
# the colour DS4Windows sends right after it connects, and DS4Windows never sends it again. So a few seconds after every
# connect, ask ds4-helper.ps1 ("start" = keep running + re-load the profile) to push the colour once more.
$req = Join-Path $PSScriptRoot "ds4-request.txt"
$log = "$env:APPDATA\DS4Windows\Logs\ds4windows_log.txt"

function Last-Connect { Get-Content $log -Tail 40 -ErrorAction SilentlyContinue | Where-Object { $_ -match 'Found Controller' } | Select-Object -Last 1 }
function Relight {
    Set-Content $req "start"
    schtasks /run /tn "LunaTV DS4Windows" | Out-Null
    for ($i = 0; $i -lt 40 -and (Test-Path $req); $i++) { Start-Sleep -Milliseconds 500 }  # helper deletes it when done
}

$seen = Last-Connect  # ignore connects from before this script started
while ($true) {
    Start-Sleep 1
    $now = Last-Connect
    if ($now -and $now -ne $seen) {
        $seen = $now
        foreach ($wait in 4, 6) { Start-Sleep $wait; Relight }  # ~4s and ~10s after connect, in case the first is still too early
    }
}
