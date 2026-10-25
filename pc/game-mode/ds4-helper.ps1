# Runs elevated (scheduled task "LunaTV DS4Windows") because DS4Windows is set to run as administrator.
# server.py writes one request line to ds4-request.txt ("profile <slot> <name>", "start", "stop" or "restart") and
# triggers the task; this script carries it out and deletes the file to signal it is done.
$req = Join-Path $PSScriptRoot "ds4-request.txt"
$exe = "C:\Program Files\DS4Windows\DS4Windows.exe"
if (-not (Test-Path $req)) { exit }
$line = (Get-Content $req -Raw).Trim()

function Running { [bool](Get-Process DS4Windows -ErrorAction SilentlyContinue) }
function Start-Ds4 {
    if (Running) { return }
    Start-Process $exe -ArgumentList "-m"
    for ($i = 0; $i -lt 20 -and -not (Running); $i++) { Start-Sleep -Milliseconds 500 }
    Start-Sleep 3  # let it finish loading before it accepts commands
}
# Re-load the controller's saved profile so its light bar colour is sent again after a (re)start.
function Reapply-Profile {
    $cur = [regex]::Match((Get-Content "$env:APPDATA\DS4Windows\Profiles.xml" -Raw), '<Controller1>([^<]+)</Controller1>').Groups[1].Value
    if ($cur) { Start-Process $exe -ArgumentList "-command", "`"LoadProfile.1.$cur`"" -Wait }
}
function Stop-Ds4 {
    if (-not (Running)) { return }
    Start-Process $exe -ArgumentList "-command", "Shutdown" -Wait
    for ($i = 0; $i -lt 20 -and (Running); $i++) { Start-Sleep -Milliseconds 500 }
    Stop-Process -Name DS4Windows -Force -ErrorAction SilentlyContinue
}

try {
    switch -Regex ($line) {
        '^profile (\d) (.+)$' { Start-Ds4; Start-Process $exe -ArgumentList "-command", "`"LoadProfile.$($Matches[1]).$($Matches[2])`"" -Wait }
        '^start$'        { Start-Ds4; Reapply-Profile }
        '^stop$'         { Stop-Ds4 }
        '^restart$'      { Stop-Ds4; Start-Ds4; Reapply-Profile }
    }
} finally {
    Remove-Item $req -Force -ErrorAction SilentlyContinue
}
